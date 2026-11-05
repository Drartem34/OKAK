package com.okak.taxi.entity;

import com.okak.taxi.service.TaxiService;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A boat (or raft) driven by an NPC driver. It ignores physics and player input: the
 * server-side {@link TaxiService} moves it along a pre-computed route every tick.
 */
public class TaxiEntity extends AbstractBoat {
	private static final EntityDataAccessor<Boolean> DATA_MOVING = SynchedEntityData.defineId(TaxiEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> DATA_COLOR = SynchedEntityData.defineId(TaxiEntity.class, EntityDataSerializers.INT);
	private static final float PADDLE_SPEED = (float) (Math.PI / 8);

	private final TaxiVariant variant;
	private final float[] paddlePositions = new float[2];
	private int orphanTicks;

	public TaxiEntity(EntityType<? extends TaxiEntity> type, Level level, TaxiVariant variant) {
		super(type, level, variant.item);
		this.variant = variant;
		this.noPhysics = true;
	}

	public TaxiVariant getVariant() {
		return this.variant;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder entityData) {
		super.defineSynchedData(entityData);
		entityData.define(DATA_MOVING, false);
		entityData.define(DATA_COLOR, 0xFFFFFF);
	}

	/** Glow outline colour, depends on the taxi class (driver stars). */
	public void setClassColor(int rgb) {
		this.entityData.set(DATA_COLOR, rgb);
	}

	@Override
	public int getTeamColor() {
		return this.entityData.get(DATA_COLOR);
	}

	public void setMoving(boolean moving) {
		if (this.entityData.get(DATA_MOVING) != moving) {
			this.entityData.set(DATA_MOVING, moving);
		}
	}

	public boolean isMoving() {
		return this.entityData.get(DATA_MOVING);
	}

	@Override
	protected double rideHeight(EntityDimensions dimensions) {
		return this.variant.raft ? dimensions.height() * 0.8888889F : dimensions.height() / 3.0F;
	}

	@Override
	public void tick() {
		// Deliberately skip the vanilla boat physics: movement is fully server driven.
		this.baseTick();

		if (this.level().isClientSide()) {
			boolean moving = this.isMoving();
			for (int i = 0; i < 2; i++) {
				this.paddlePositions[i] = moving ? this.paddlePositions[i] + PADDLE_SPEED : 0.0F;
			}
		} else if (!TaxiService.isActiveTaxi(this)) {
			// Left over from a crash, a reload or a cancelled order: drive away.
			if (++this.orphanTicks > 40) {
				this.ejectPassengers();
				this.discard();
			}
		} else {
			this.orphanTicks = 0;
		}
	}

	@Override
	public boolean getPaddleState(int side) {
		return this.isMoving();
	}

	@Override
	public float getRowingTime(int side, float a) {
		return this.isMoving() ? Mth.clampedLerp(a, this.paddlePositions[side] - PADDLE_SPEED, this.paddlePositions[side]) : 0.0F;
	}

	@Override
	public @Nullable LivingEntity getControllingPassenger() {
		// Nobody but the driver steers this boat.
		return null;
	}

	@Override
	public boolean canBeCollidedWith(@Nullable Entity other) {
		return false;
	}

	@Override
	public boolean canCollideWith(Entity entity) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public void push(Entity entity) {
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean hurtClient(DamageSource source) {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		if (this.level().isClientSide()) {
			return true;
		}
		return this.getPassengers().size() < this.getMaxPassengers() && TaxiService.canBoard(this, passenger);
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		if (player.isSecondaryUseActive()) {
			return InteractionResult.PASS;
		}
		if (this.level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (player instanceof ServerPlayer serverPlayer && TaxiService.onInteract(this, serverPlayer)) {
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		if (!this.level().isClientSide()) {
			TaxiService.onPassengerRemoved(this, passenger);
		}
	}
}
