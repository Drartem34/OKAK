package com.okak.taxi.service;

import com.okak.taxi.entity.TaxiEntities;
import com.okak.taxi.entity.TaxiEntity;
import com.okak.taxi.entity.TaxiVariant;
import com.okak.taxi.explore.ChunkKey;
import com.okak.taxi.explore.ExplorationTracker;
import com.okak.taxi.explore.ExploredData;
import com.okak.taxi.net.TaxiStatusS2C;
import com.okak.taxi.route.Route;
import com.okak.taxi.route.RouteFinder;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * The taxi company. Keeps one session per player (destination marker + current order) and
 * drives every taxi along its route on the server tick.
 */
public final class TaxiService {
	public static final String OPEN_MAP_KEY = "key.taxi.open_map";
	public static final int DRIVER_COUNT = 12;

	private static final double APPROACH_SPEED = 0.6;
	private static final double MIN_RIDE_SPEED = 0.7;
	private static final double MAX_RIDE_SPEED = 1.6;
	private static final double TARGET_RIDE_TICKS = 20 * 45;
	private static final double SPAWN_DISTANCE = 48;
	private static final int WAIT_LIMIT = 20 * 180;
	private static final int FINISH_TICKS = 40;
	private static final int STATUS_INTERVAL = 10;
	private static final int MAX_ROUTE_POINTS = 400;

	private enum State {
		APPROACHING, WAITING, RIDING, FINISHED
	}

	private static final class Session {
		final UUID player;
		double @Nullable [] destination;
		@Nullable Route preview;
		@Nullable Order order;

		Session(UUID player) {
			this.player = player;
		}
	}

	private static final class Order {
		final TaxiVariant variant;
		final int driver;
		final int rating10;
		final String plate;
		final ResourceKey<Level> dimension;
		final double pickupX;
		final double pickupZ;
		State state = State.APPROACHING;
		int stateTicks;
		Route route;
		double progress;
		double speed;
		@Nullable TaxiEntity entity;
		double y;
		float yaw;

		Order(TaxiVariant variant, int driver, int rating10, String plate, ResourceKey<Level> dimension, double pickupX, double pickupZ, Route route) {
			this.variant = variant;
			this.driver = driver;
			this.rating10 = rating10;
			this.plate = plate;
			this.dimension = dimension;
			this.pickupX = pickupX;
			this.pickupZ = pickupZ;
			this.route = route;
			this.speed = APPROACH_SPEED;
		}

		void setState(State state) {
			this.state = state;
			this.stateTicks = 0;
		}
	}

	private static final Map<UUID, Session> SESSIONS = new HashMap<>();
	/** taxi entity uuid -> owner player uuid. */
	private static final Map<UUID, UUID> TAXI_OWNERS = new HashMap<>();

	private TaxiService() {
	}

	// ------------------------------------------------------------------ player requests

	public static void setDestination(ServerPlayer player, boolean clear, int x, int z) {
		Session session = session(player);
		Order order = session.order;

		if (clear) {
			if (order != null && order.state == State.RIDING) {
				actionBar(player, Component.translatable("taxi.msg.cannot_clear_riding"));
			} else {
				session.destination = null;
				session.preview = null;
			}
			sendStatus(player);
			return;
		}

		if (!ExplorationTracker.isSupported(player.level())) {
			actionBar(player, Component.translatable("taxi.msg.unsupported_dimension"));
			return;
		}

		LongSet explored = explored(player);
		if (!explored.contains(ChunkKey.ofBlock(x, z))) {
			actionBar(player, Component.translatable("taxi.msg.unexplored").withStyle(ChatFormatting.RED));
			sendStatus(player);
			return;
		}

		double destX = x + 0.5;
		double destZ = z + 0.5;

		if (order != null && order.state == State.RIDING && order.entity != null) {
			Route route = RouteFinder.findRoute(explored, order.entity.getX(), order.entity.getZ(), destX, destZ);
			if (route == null) {
				actionBar(player, Component.translatable("taxi.msg.no_route").withStyle(ChatFormatting.RED));
				sendStatus(player);
				return;
			}
			session.destination = new double[]{destX, destZ};
			session.preview = null;
			order.route = route;
			order.progress = 0;
			order.speed = rideSpeed(route);
			message(player, Component.translatable("taxi.msg.route_changed", time(route.length() / order.speed)));
			sendStatus(player);
			return;
		}

		double fromX = order != null ? order.pickupX : player.getX();
		double fromZ = order != null ? order.pickupZ : player.getZ();
		Route route = RouteFinder.findRoute(explored, fromX, fromZ, destX, destZ);
		if (route == null) {
			actionBar(player, Component.translatable("taxi.msg.no_route").withStyle(ChatFormatting.RED));
			session.destination = null;
			session.preview = null;
			sendStatus(player);
			return;
		}

		session.destination = new double[]{destX, destZ};
		session.preview = route;
		sendStatus(player);
	}

	public static void call(ServerPlayer player) {
		Session session = session(player);
		if (session.order != null && session.order.state != State.FINISHED) {
			actionBar(player, Component.translatable("taxi.msg.already_ordered"));
			return;
		}
		ServerLevel level = player.level();
		if (!ExplorationTracker.isSupported(level)) {
			actionBar(player, Component.translatable("taxi.msg.unsupported_dimension").withStyle(ChatFormatting.RED));
			return;
		}
		if (player.isPassenger()) {
			actionBar(player, Component.translatable("taxi.msg.dismount_first").withStyle(ChatFormatting.RED));
			return;
		}
		if (session.order != null) {
			removeTaxi(session.order);
			session.order = null;
		}

		ExplorationTracker.markAround(player, level, player.getBlockX() >> 4, player.getBlockZ() >> 4);
		RandomSource random = level.getRandom();
		RouteFinder.Approach approach = RouteFinder.planApproach(explored(player), player.getX(), player.getZ(), random);

		TaxiVariant variant = TaxiVariant.byIndex(random.nextInt(TaxiVariant.count()));
		int driver = random.nextInt(DRIVER_COUNT);
		int rating = random.nextInt(3) == 0 ? 50 - random.nextInt(6) : 50;
		String plate = variant.plateWord() + " " + String.format(Locale.ROOT, "%02d", 1 + random.nextInt(99));
		Order order = new Order(variant, driver, rating, plate, level.dimension(), player.getX(), player.getZ(), approach.route());
		session.order = order;

		// Re-plan the trip from the pickup point if a destination is already set.
		if (session.destination != null) {
			session.preview = RouteFinder.findRoute(explored(player), order.pickupX, order.pickupZ, session.destination[0], session.destination[1]);
		}

		message(player, Component.translatable("taxi.msg.called", driverName(driver), time(approach.route().length() / APPROACH_SPEED)));
		sendStatus(player);
	}

	public static void cancelByPlayer(ServerPlayer player) {
		Session session = SESSIONS.get(player.getUUID());
		if (session == null || session.order == null || session.order.state == State.FINISHED) {
			sendStatus(player);
			return;
		}
		endOrder(player, session, Component.translatable("taxi.msg.cancelled"));
	}

	// ------------------------------------------------------------------ entity callbacks

	public static boolean isActiveTaxi(TaxiEntity taxi) {
		return TAXI_OWNERS.containsKey(taxi.getUUID());
	}

	public static boolean canBoard(TaxiEntity taxi, Entity passenger) {
		UUID owner = TAXI_OWNERS.get(taxi.getUUID());
		if (owner == null || !owner.equals(passenger.getUUID())) {
			return false;
		}
		Session session = SESSIONS.get(owner);
		return session != null && session.order != null && session.order.entity == taxi
				&& (session.order.state == State.WAITING || session.order.state == State.APPROACHING);
	}

	public static boolean onInteract(TaxiEntity taxi, ServerPlayer player) {
		UUID owner = TAXI_OWNERS.get(taxi.getUUID());
		if (owner == null) {
			return false;
		}
		if (!owner.equals(player.getUUID())) {
			actionBar(player, Component.translatable("taxi.msg.not_yours").withStyle(ChatFormatting.RED));
			return true;
		}
		Session session = SESSIONS.get(owner);
		if (session == null || session.order == null || session.order.entity != taxi) {
			return false;
		}
		Order order = session.order;
		if (order.state == State.APPROACHING) {
			// The player ran up to the taxi - the driver stops right here.
			order.setState(State.WAITING);
			taxi.setMoving(false);
		}
		if (order.state != State.WAITING) {
			return true;
		}
		if (player.isPassenger()) {
			player.stopRiding();
		}
		if (player.startRiding(taxi) && session.destination == null) {
			message(player, Component.translatable("taxi.msg.set_destination", Component.keybind(OPEN_MAP_KEY)));
		}
		return true;
	}

	public static void onPassengerRemoved(TaxiEntity taxi, Entity passenger) {
		UUID owner = TAXI_OWNERS.get(taxi.getUUID());
		if (owner == null || !owner.equals(passenger.getUUID())) {
			return;
		}
		Session session = SESSIONS.get(owner);
		if (session == null || session.order == null || session.order.entity != taxi) {
			return;
		}
		if (session.order.state == State.RIDING && passenger instanceof ServerPlayer player) {
			session.order.setState(State.FINISHED);
			taxi.setMoving(false);
			message(player, Component.translatable("taxi.msg.left"));
			sendStatus(player);
		}
	}

	// ------------------------------------------------------------------ lifecycle

	public static void onDisconnect(ServerPlayer player) {
		Session session = SESSIONS.remove(player.getUUID());
		if (session != null && session.order != null) {
			removeTaxi(session.order);
		}
	}

	public static void onDimensionChange(ServerPlayer player) {
		Session session = SESSIONS.get(player.getUUID());
		if (session == null) {
			return;
		}
		session.destination = null;
		session.preview = null;
		if (session.order != null) {
			endOrder(player, session, Component.translatable("taxi.msg.lost"));
		} else {
			sendStatus(player);
		}
	}

	public static void clear() {
		for (Session session : SESSIONS.values()) {
			if (session.order != null) {
				removeTaxi(session.order);
			}
		}
		SESSIONS.clear();
		TAXI_OWNERS.clear();
	}

	// ------------------------------------------------------------------ ticking

	public static void tick(MinecraftServer server) {
		if (SESSIONS.isEmpty()) {
			return;
		}
		int tick = server.getTickCount();
		Iterator<Session> iterator = SESSIONS.values().iterator();
		while (iterator.hasNext()) {
			Session session = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(session.player);
			if (player == null) {
				if (session.order != null) {
					removeTaxi(session.order);
				}
				iterator.remove();
				continue;
			}

			Order order = session.order;
			if (order == null) {
				continue;
			}
			ServerLevel level = player.level();
			if (level.dimension() != order.dimension) {
				endOrder(player, session, Component.translatable("taxi.msg.lost"));
				continue;
			}

			order.stateTicks++;
			switch (order.state) {
				case APPROACHING -> tickApproaching(player, order, level);
				case WAITING -> tickWaiting(player, session, order, level);
				case RIDING -> tickRiding(player, session, order, level);
				case FINISHED -> {
					if (order.stateTicks > FINISH_TICKS) {
						session.order = null;
						removeTaxi(order);
						sendStatus(player);
					}
				}
			}

			if (session.order != null && tick % STATUS_INTERVAL == 0) {
				sendStatus(player);
			}
		}
	}

	private static void tickApproaching(ServerPlayer player, Order order, ServerLevel level) {
		checkEntity(order);
		double total = order.route.length();
		double remaining = total - order.progress;
		double factor = Mth.clamp(remaining / 16.0, 0.25, 1.0);
		order.progress = Math.min(total, order.progress + APPROACH_SPEED * factor);

		if (order.entity == null && total - order.progress <= SPAWN_DISTANCE) {
			trySpawn(order, level, player, order.route.at(order.progress));
		}
		if (order.entity != null) {
			order.entity.setMoving(true);
			moveEntity(order, level);
		}

		if (order.progress >= total) {
			if (order.entity == null) {
				trySpawn(order, level, player, new double[]{order.pickupX, order.pickupZ});
			}
			if (order.entity != null) {
				order.entity.setMoving(false);
			}
			order.setState(State.WAITING);
			message(player, Component.translatable("taxi.msg.arrived", driverName(order.driver), Component.translatable("taxi.vehicle." + order.variant.id)));
			level.playSound(null, order.pickupX, player.getY(), order.pickupZ, SoundEvents.NOTE_BLOCK_BELL, SoundSource.NEUTRAL, 1.0F, 1.2F);
			sendStatus(player);
		}
	}

	private static void tickWaiting(ServerPlayer player, Session session, Order order, ServerLevel level) {
		checkEntity(order);
		if (order.entity == null) {
			trySpawn(order, level, player, new double[]{order.pickupX, order.pickupZ});
		}
		TaxiEntity taxi = order.entity;
		if (taxi != null) {
			taxi.setMoving(false);
			if (taxi.hasPassenger(player)) {
				if (session.destination != null) {
					startRide(player, session, order);
				} else if (order.stateTicks % 200 == 0) {
					actionBar(player, Component.translatable("taxi.msg.set_destination", Component.keybind(OPEN_MAP_KEY)));
				}
				return;
			}
		}

		if (order.stateTicks > WAIT_LIMIT) {
			endOrder(player, session, Component.translatable("taxi.msg.timeout", driverName(order.driver)));
		} else if (order.stateTicks % 300 == 0) {
			actionBar(player, Component.translatable("taxi.msg.waiting", driverName(order.driver)));
		}
	}

	private static void startRide(ServerPlayer player, Session session, Order order) {
		TaxiEntity taxi = order.entity;
		if (taxi == null || session.destination == null) {
			return;
		}
		Route route = RouteFinder.findRoute(explored(player), taxi.getX(), taxi.getZ(), session.destination[0], session.destination[1]);
		if (route == null) {
			actionBar(player, Component.translatable("taxi.msg.no_route").withStyle(ChatFormatting.RED));
			session.destination = null;
			session.preview = null;
			sendStatus(player);
			return;
		}
		order.route = route;
		order.progress = 0;
		order.speed = rideSpeed(route);
		order.y = taxi.getY();
		order.yaw = taxi.getYRot();
		order.setState(State.RIDING);
		session.preview = null;
		taxi.setMoving(true);
		message(player, Component.translatable("taxi.msg.ride_started", time(route.length() / order.speed)));
		sendStatus(player);
	}

	private static void tickRiding(ServerPlayer player, Session session, Order order, ServerLevel level) {
		if (!checkEntity(order) || !order.entity.hasPassenger(player)) {
			endOrder(player, session, Component.translatable("taxi.msg.left"));
			return;
		}
		double total = order.route.length();
		double remaining = total - order.progress;
		double factor = Mth.clamp(Math.min(order.progress + 4.0, remaining) / 20.0, 0.2, 1.0);
		order.progress = Math.min(total, order.progress + order.speed * factor);
		moveEntity(order, level);

		if (order.progress >= total) {
			TaxiEntity taxi = order.entity;
			order.setState(State.FINISHED);
			taxi.setMoving(false);
			taxi.ejectPassengers();
			session.destination = null;
			session.preview = null;
			message(player, Component.translatable("taxi.msg.trip_done", driverName(order.driver)));
			level.playSound(null, taxi.getX(), taxi.getY(), taxi.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.6F, 1.4F);
			sendStatus(player);
		}
	}

	// ------------------------------------------------------------------ taxi entity helpers

	/** Returns true if the order still has a live taxi entity. */
	private static boolean checkEntity(Order order) {
		if (order.entity != null && order.entity.isRemoved()) {
			TAXI_OWNERS.remove(order.entity.getUUID());
			order.entity = null;
		}
		return order.entity != null;
	}

	private static void trySpawn(Order order, ServerLevel level, ServerPlayer owner, double[] pos) {
		BlockPos blockPos = BlockPos.containing(pos[0], owner.getY(), pos[1]);
		if (!level.isPositionEntityTicking(blockPos)) {
			return;
		}
		TaxiEntity taxi = new TaxiEntity(TaxiEntities.TYPES.get(order.variant), level, order.variant);
		order.y = surfaceY(level, pos[0], pos[1], owner.getY());
		double[] ahead = order.route.at(order.progress + 3.0);
		double dx = ahead[0] - pos[0];
		double dz = ahead[1] - pos[1];
		if (dx * dx + dz * dz > 0.01) {
			order.yaw = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
		}
		taxi.setInitialPos(pos[0], order.y, pos[1]);
		taxi.setYRot(order.yaw);
		taxi.setCustomName(Component.translatable("taxi.nameplate", driverName(order.driver), rating(order.rating10), order.plate)
				.withStyle(ChatFormatting.YELLOW));
		taxi.setCustomNameVisible(true);
		if (level.addFreshEntity(taxi)) {
			order.entity = taxi;
			TAXI_OWNERS.put(taxi.getUUID(), owner.getUUID());
			level.sendParticles(ParticleTypes.CLOUD, pos[0], order.y + 0.5, pos[1], 12, 0.6, 0.3, 0.6, 0.02);
		}
	}

	private static void moveEntity(Order order, ServerLevel level) {
		TaxiEntity taxi = order.entity;
		if (taxi == null) {
			return;
		}
		double[] pos = order.route.at(order.progress);
		double target = surfaceY(level, pos[0], pos[1], order.y);
		for (double ahead = 1.5; ahead <= 4.5; ahead += 1.5) {
			double[] p = order.route.at(order.progress + ahead);
			target = Math.max(target, surfaceY(level, p[0], p[1], order.y));
		}
		order.y = target > order.y ? Math.min(target, order.y + 0.7) : Math.max(target, order.y - 0.3);

		double[] ahead = order.route.at(order.progress + 3.0);
		double dx = ahead[0] - pos[0];
		double dz = ahead[1] - pos[1];
		if (dx * dx + dz * dz > 0.01) {
			float targetYaw = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
			order.yaw = Mth.approachDegrees(order.yaw, targetYaw, 12.0F);
		}
		taxi.setPos(pos[0], order.y, pos[1]);
		taxi.setYRot(order.yaw);
	}

	/** Height the boat should sit at: on top of the ground, floating on water, keeping height over the void. */
	private static double surfaceY(ServerLevel level, double x, double z, double fallback) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		if (!level.hasChunk(bx >> 4, bz >> 4)) {
			level.getChunk(bx >> 4, bz >> 4);
		}
		int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
		if (height <= level.getMinY()) {
			return fallback;
		}
		if (level.getFluidState(new BlockPos(bx, height - 1, bz)).is(FluidTags.WATER)) {
			return height - 0.15;
		}
		return height;
	}

	private static void removeTaxi(Order order) {
		TaxiEntity taxi = order.entity;
		order.entity = null;
		if (taxi == null) {
			return;
		}
		TAXI_OWNERS.remove(taxi.getUUID());
		if (!taxi.isRemoved()) {
			if (taxi.level() instanceof ServerLevel level) {
				level.sendParticles(ParticleTypes.CLOUD, taxi.getX(), taxi.getY() + 0.5, taxi.getZ(), 12, 0.6, 0.3, 0.6, 0.02);
			}
			taxi.ejectPassengers();
			taxi.discard();
		}
	}

	private static void endOrder(ServerPlayer player, Session session, @Nullable Component message) {
		Order order = session.order;
		if (order == null) {
			return;
		}
		session.order = null;
		removeTaxi(order);
		if (message != null) {
			message(player, message);
		}
		sendStatus(player);
	}

	// ------------------------------------------------------------------ status sync

	public static void sendStatus(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, TaxiStatusS2C.TYPE)) {
			return;
		}
		Session session = SESSIONS.get(player.getUUID());
		if (session == null) {
			ServerPlayNetworking.send(player, TaxiStatusS2C.empty());
			return;
		}

		boolean hasDest = session.destination != null;
		int destX = hasDest ? Mth.floor(session.destination[0]) : 0;
		int destZ = hasDest ? Mth.floor(session.destination[1]) : 0;
		Order order = session.order;

		if (order == null) {
			Route preview = session.preview;
			int[] points = preview != null ? preview.remainingPoints(0, MAX_ROUTE_POINTS) : new int[0];
			int distance = preview != null ? (int) preview.length() : 0;
			int eta = preview != null ? (int) (preview.length() / rideSpeed(preview)) : 0;
			ServerPlayNetworking.send(player, new TaxiStatusS2C(TaxiStatusS2C.NONE, hasDest, destX, destZ,
					0, 0, 0, 0, eta, distance, 0, 50, "", 0, points));
			return;
		}

		double[] taxiPos = order.entity != null ? new double[]{order.entity.getX(), order.entity.getZ()} : order.route.at(order.progress);
		double remaining = Math.max(0, order.route.length() - order.progress);
		int state;
		int eta = 0;
		int distance = 0;
		int[] points = new int[0];
		switch (order.state) {
			case APPROACHING -> {
				state = TaxiStatusS2C.APPROACHING;
				eta = (int) (remaining / APPROACH_SPEED);
				distance = (int) remaining;
				points = order.route.remainingPoints(order.progress, MAX_ROUTE_POINTS);
			}
			case WAITING -> {
				state = TaxiStatusS2C.WAITING;
				if (session.preview != null) {
					eta = (int) (session.preview.length() / rideSpeed(session.preview));
					distance = (int) session.preview.length();
					points = session.preview.remainingPoints(0, MAX_ROUTE_POINTS);
				}
			}
			case RIDING -> {
				state = TaxiStatusS2C.RIDING;
				eta = (int) (remaining / order.speed);
				distance = (int) remaining;
				points = order.route.remainingPoints(order.progress, MAX_ROUTE_POINTS);
			}
			default -> state = TaxiStatusS2C.FINISHED;
		}

		ServerPlayNetworking.send(player, new TaxiStatusS2C(state, hasDest, destX, destZ,
				Mth.floor(taxiPos[0]), Mth.floor(taxiPos[1]), Mth.floor(order.pickupX), Mth.floor(order.pickupZ),
				eta, distance, order.driver, order.rating10, order.plate, order.variant.ordinal(), points));
	}

	// ------------------------------------------------------------------ misc helpers

	private static Session session(ServerPlayer player) {
		return SESSIONS.computeIfAbsent(player.getUUID(), Session::new);
	}

	private static LongSet explored(ServerPlayer player) {
		return ExploredData.of(player).dimension(ExplorationTracker.dimensionId(player.level())).keySet();
	}

	private static double rideSpeed(Route route) {
		return Mth.clamp(route.length() / TARGET_RIDE_TICKS, MIN_RIDE_SPEED, MAX_RIDE_SPEED);
	}

	public static MutableComponent driverName(int driver) {
		return Component.translatable("taxi.driver." + driver);
	}

	private static String rating(int rating10) {
		return String.format(Locale.ROOT, "%d.%d", rating10 / 10, rating10 % 10);
	}

	private static MutableComponent time(double ticks) {
		int seconds = Math.max(1, (int) Math.ceil(ticks / 20.0));
		if (seconds < 60) {
			return Component.translatable("taxi.time.seconds", seconds);
		}
		return Component.translatable("taxi.time.minutes", seconds / 60, seconds % 60);
	}

	private static void message(ServerPlayer player, Component text) {
		player.sendSystemMessage(Component.translatable("taxi.prefix").withStyle(ChatFormatting.GOLD).append(" ").append(text.copy().withStyle(ChatFormatting.WHITE)));
	}

	private static void actionBar(ServerPlayer player, Component text) {
		player.sendSystemMessage(text, true);
	}
}
