package com.sdevuyst.pingyswaddles.entity.custom;

import com.google.common.collect.Lists;
import com.google.common.collect.UnmodifiableIterator;
import com.sdevuyst.pingyswaddles.item.ModItems;
import net.minecraft.BlockUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Rotations;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.awt.event.InputEvent;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntFunction;

public abstract class AbstractSurfboard extends Entity {

    private static final EntityDataAccessor<Integer> DATA_ID_HURT;
    private static final EntityDataAccessor<Integer> DATA_ID_HURTDIR;
    private static final EntityDataAccessor<Float> DATA_ID_DAMAGE;
    private static final EntityDataAccessor<Integer> DATA_ID_TYPE;
    private static final EntityDataAccessor<Integer> DATA_ID_BUBBLE_TIME;
    private static final EntityDataAccessor<Boolean> DATA_ID_PADDLING;
    private float invFriction;
    private float outOfControlTicks;
    private float deltaRotation;
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;
    private double lerpXRot;
    private boolean inputLeft;
    private boolean inputRight;
    private boolean inputUp;
    private boolean inputDown;
    private double waterLevel;
    private float landFriction;
    private SurfboardEntity.Status status;
    private SurfboardEntity.Status oldStatus;
    private double lastYd;
    private boolean isAboveBubbleColumn;
    private boolean bubbleColumnDirectionIsDown;
    private float bubbleMultiplier;
    private float bubbleAngle;
    private float bubbleAngleO;

    public AbstractSurfboard(EntityType<?> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
        this.blocksBuilding = true;
    }

    public AbstractSurfboard(EntityType<?> pEntityType, Level pLevel, double pX, double pY, double pZ) {
        this(pEntityType, pLevel);
        this.setPos(pX, pY, pZ);
        this.xo = pX;
        this.yo = pY;
        this.zo = pZ;
    }

    @Override
    protected float getEyeHeight(Pose pPose, EntityDimensions pSize) {
        return pSize.height;
    }

    protected Entity.MovementEmission getMovementEmission() {
        return MovementEmission.EVENTS;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_ID_HURT, 0);
        this.entityData.define(DATA_ID_HURTDIR, 1);
        this.entityData.define(DATA_ID_DAMAGE, 0.0F);
        this.entityData.define(DATA_ID_TYPE, SurfboardEntity.Type.OAK.ordinal());
        this.entityData.define(DATA_ID_BUBBLE_TIME, 0);
        this.entityData.define(DATA_ID_PADDLING, false);
    }

    @Override
    public boolean canCollideWith(Entity pEntity) {
        return canVehicleCollide(this, pEntity);
    }

    public static boolean canVehicleCollide(Entity pVehicle, Entity pEntity) {
        return (pEntity.canBeCollidedWith() || pEntity.isPushable()) && !pVehicle.isPassengerOfSameVehicle(pEntity);
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return true;
    }

    @Override
    protected Vec3 getRelativePortalPosition(Direction.Axis pAxis, BlockUtil.FoundRectangle pPortal) {
        return LivingEntity.resetForwardDirectionOfRelativePortalPosition(super.getRelativePortalPosition(pAxis, pPortal));
    }

    @Override
    public double getPassengersRidingOffset() {
        return 0.4F;
    }

    @Override
    public boolean hurt(DamageSource pSource, float pAmount) {

        if (this.isInvulnerableTo(pSource)) {
            return false;
        } else if (!this.level().isClientSide && !this.isRemoved()) {
            this.setHurtDir(-this.getHurtDir());
            this.setHurtTime(10);
            this.setDamage(this.getDamage() + pAmount * 10.0F);
            this.markHurt();
            this.gameEvent(GameEvent.ENTITY_DAMAGE, pSource.getEntity());
            boolean flag = pSource.getEntity() instanceof Player && ((Player)pSource.getEntity()).getAbilities().instabuild;
            if (flag || this.getDamage() > 40.0F) {
                if (!flag && this.level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
                    this.destroy(pSource);
                }

                this.discard();
            }

            return true;
        } else {
            return true;
        }
    }

    protected void destroy(DamageSource pDamageSource) {
        this.spawnAtLocation(this.getDropItem());
    }


    public void push(Entity pEntity) {
        if (pEntity instanceof SurfboardEntity) {
            if (pEntity.getBoundingBox().minY < this.getBoundingBox().maxY) {
                super.push(pEntity);
            }
        } else if (pEntity.getBoundingBox().minY <= this.getBoundingBox().minY) {
            super.push(pEntity);
        }

    }

    public Item getDropItem() {
        Item item;
        switch (this.getVariant()) {
            case OAK:
                item = ModItems.OAK_SURFBOARD.get();
                break;
            // FEATURE more variants
            default:
                item = Items.OAK_BOAT;
        }

        return item;
    }

    public void animateHurt(float pYaw) {
        this.setHurtDir(-this.getHurtDir());
        this.setHurtTime(10);
        this.setDamage(this.getDamage() * 11.0F);
    }

    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public void lerpTo(double pX, double pY, double pZ, float pYaw, float pPitch, int pPosRotationIncrements, boolean pTeleport) {
        this.lerpX = pX;
        this.lerpY = pY;
        this.lerpZ = pZ;
        this.lerpYRot = (double)pYaw;
        this.lerpXRot = (double)pPitch;
        this.lerpSteps = 10;
    }

    @Override
    public @NotNull Direction getMotionDirection() {
        return this.getDirection().getClockWise();
    }

    @Override
    public void tick() {
        this.oldStatus = this.status;
        this.status = this.getStatus();

        // out of control surfboard
        if (this.status != SurfboardEntity.Status.UNDER_WATER && this.status != SurfboardEntity.Status.UNDER_FLOWING_WATER) {
            this.outOfControlTicks = 0.0F;
        } else {
            ++this.outOfControlTicks;
        }

        // eject passengers if out of control for more than 60 ticks (3 sec)
        if (!this.level().isClientSide && this.outOfControlTicks >= 60.0F) {
            this.ejectPassengers();
        }

        if (this.getHurtTime() > 0) {
            this.setHurtTime(this.getHurtTime() - 1);
        }

        if (this.getDamage() > 0.0F) {
            this.setDamage(this.getDamage() - 1.0F);
        }

        super.tick();
        this.tickLerp();

        // player is controlling surfboard
        if (this.isControlledByLocalInstance()) {

            this.floatSurfboard();
            if (this.level().isClientSide) {
                this.controlSurfboard();
            }

            // check inputs
            this.inputUp = Minecraft.getInstance().options.keyUp.isDown();
            this.inputDown = Minecraft.getInstance().options.keyDown.isDown();
            this.inputLeft = Minecraft.getInstance().options.keyLeft.isDown();
            this.inputRight= Minecraft.getInstance().options.keyRight.isDown();

            this.setPaddling(
                    (this.inputRight || this.inputLeft || this.inputUp || this.inputDown)
                    && this.isControlledByLocalInstance()
            );

            // actually move the surfboard
            this.move(MoverType.SELF, this.getDeltaMovement());

            // spawn particles
            if (this.isPaddling()) {
                Vec3 delta = this.getDeltaMovement();
                Direction direction = this.getDirection();

                for (int bubbleCount = 0; bubbleCount < 30; bubbleCount++) {

                    this.level().addParticle(
                            ParticleTypes.BUBBLE,
                            this.getX() + delta.x,
                            this.getY() + delta.y - 0.01F,
                            this.getZ() + delta.z,
                            direction.getStepX(),
                            direction.getStepY(),
                            direction.getStepZ()
                    );
                }
            }

        } else {
            // don't move anything
            this.setDeltaMovement(Vec3.ZERO);
        }

        // play paddling sounds
        if (this.isPaddling() && isControlledByLocalInstance()) {
            SoundEvent soundevent = this.getPaddleSound();
            if (soundevent != null) {
                Vec3 vec3 = this.getViewVector(1.0F);
                this.level().playSound((Player)null, this.getX() + vec3.z, this.getY(), this.getZ() + vec3.x, soundevent, this.getSoundSource(), 1.0F, 0.8F + 0.4F * this.random.nextFloat());
            }
        }

        this.checkInsideBlocks();

        // non-player entities start riding surfboat when getting closeby
        List<Entity> list = this.level().getEntities(this, this.getBoundingBox().inflate(0.20000000298023224, -0.009999999776482582, 0.20000000298023224), EntitySelector.pushableBy(this));
        if (!list.isEmpty()) {
            boolean flag = !this.level().isClientSide && !(this.getControllingPassenger() instanceof Player);

            for (int j = 0; j < list.size(); ++j) {
                Entity entity = (Entity)list.get(j);
                if (!entity.hasPassenger(this)) {
                    if (flag && this.getPassengers().size() < this.getMaxPassengers() && !entity.isPassenger() && this.hasEnoughSpaceFor(entity) && entity instanceof LivingEntity && !(entity instanceof WaterAnimal) && !(entity instanceof Player)) {
                        entity.startRiding(this);
                    } else {
                        this.push(entity);
                    }
                }
            }
        }
    }

    @Nullable
    protected SoundEvent getPaddleSound() {
        return SoundEvents.BOAT_PADDLE_WATER;
    }

    @Override
    public boolean shouldRiderSit() {
        return false;
    }

    private void tickLerp() {
        if (this.isControlledByLocalInstance()) {
            this.lerpSteps = 0;
            this.syncPacketPositionCodec(this.getX(), this.getY(), this.getZ());
        }

        if (this.lerpSteps > 0) {
            double d0 = this.getX() + (this.lerpX - this.getX()) / (double)this.lerpSteps;
            double d1 = this.getY() + (this.lerpY - this.getY()) / (double)this.lerpSteps;
            double d2 = this.getZ() + (this.lerpZ - this.getZ()) / (double)this.lerpSteps;
            double d3 = Mth.wrapDegrees(this.lerpYRot - (double)this.getYRot());
            this.setYRot(this.getYRot() + (float)d3 / (float)this.lerpSteps);
            this.setXRot(this.getXRot() + (float)(this.lerpXRot - (double)this.getXRot()) / (float)this.lerpSteps);
            --this.lerpSteps;
            this.setPos(d0, d1, d2);
            this.setRot(this.getYRot(), this.getXRot());
        }

    }

    private SurfboardEntity.Status getStatus() {
        AbstractSurfboard.Status boat$status = this.isUnderwater();
        if (boat$status != null) {
            this.waterLevel = this.getBoundingBox().maxY;
            return boat$status;
        } else if (this.checkInWater()) {
            return SurfboardEntity.Status.IN_WATER;
        } else {
            float f = this.getGroundFriction();
            if (f > 0.0F) {
                this.landFriction = f;
                return SurfboardEntity.Status.ON_LAND;
            } else {
                return SurfboardEntity.Status.IN_AIR;
            }
        }
    }

    public float getWaterLevelAbove() {
        AABB aabb = this.getBoundingBox();
        int i = Mth.floor(aabb.minX);
        int j = Mth.ceil(aabb.maxX);
        int k = Mth.floor(aabb.maxY);
        int l = Mth.ceil(aabb.maxY - this.lastYd);
        int i1 = Mth.floor(aabb.minZ);
        int j1 = Mth.ceil(aabb.maxZ);
        BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();

        label39:
        for(int k1 = k; k1 < l; ++k1) {
            float f = 0.0F;

            for(int l1 = i; l1 < j; ++l1) {
                for(int i2 = i1; i2 < j1; ++i2) {
                    blockpos$mutableblockpos.set(l1, k1, i2);
                    FluidState fluidstate = this.level().getFluidState(blockpos$mutableblockpos);
                    f = Math.max(f, fluidstate.getHeight(this.level(), blockpos$mutableblockpos));

                    if (f >= 1.0F) {
                        continue label39;
                    }
                }
            }

            if (f < 1.0F) {
                return (float)blockpos$mutableblockpos.getY() + f;
            }
        }

        return (float)(l + 1);
    }

    public float getGroundFriction() {
        AABB aabb = this.getBoundingBox();
        AABB aabb1 = new AABB(aabb.minX, aabb.minY - 0.001, aabb.minZ, aabb.maxX, aabb.minY, aabb.maxZ);
        int i = Mth.floor(aabb1.minX) - 1;
        int j = Mth.ceil(aabb1.maxX) + 1;
        int k = Mth.floor(aabb1.minY) - 1;
        int l = Mth.ceil(aabb1.maxY) + 1;
        int i1 = Mth.floor(aabb1.minZ) - 1;
        int j1 = Mth.ceil(aabb1.maxZ) + 1;
        VoxelShape voxelshape = Shapes.create(aabb1);
        float f = 0.0F;
        int k1 = 0;
        BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();

        for(int l1 = i; l1 < j; ++l1) {
            for(int i2 = i1; i2 < j1; ++i2) {
                int j2 = (l1 != i && l1 != j - 1 ? 0 : 1) + (i2 != i1 && i2 != j1 - 1 ? 0 : 1);
                if (j2 != 2) {
                    for(int k2 = k; k2 < l; ++k2) {
                        if (j2 <= 0 || k2 != k && k2 != l - 1) {
                            blockpos$mutableblockpos.set(l1, k2, i2);
                            BlockState blockstate = this.level().getBlockState(blockpos$mutableblockpos);
                            if (!(blockstate.getBlock() instanceof WaterlilyBlock) && Shapes.joinIsNotEmpty(blockstate.getCollisionShape(this.level(), blockpos$mutableblockpos).move((double)l1, (double)k2, (double)i2), voxelshape, BooleanOp.AND)) {
                                f += blockstate.getFriction(this.level(), blockpos$mutableblockpos, this);
                                ++k1;
                            }
                        }
                    }
                }
            }
        }

        return f / (float)k1;
    }

    private boolean checkInWater() {
        AABB aabb = this.getBoundingBox();
        int i = Mth.floor(aabb.minX);
        int j = Mth.ceil(aabb.maxX);
        int k = Mth.floor(aabb.minY);
        int l = Mth.ceil(aabb.minY + 0.001);
        int i1 = Mth.floor(aabb.minZ);
        int j1 = Mth.ceil(aabb.maxZ);
        boolean flag = false;
        this.waterLevel = -1.7976931348623157E308;
        BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();

        for(int k1 = i; k1 < j; ++k1) {
            for(int l1 = k; l1 < l; ++l1) {
                for(int i2 = i1; i2 < j1; ++i2) {
                    blockpos$mutableblockpos.set(k1, l1, i2);
                    FluidState fluidstate = this.level().getFluidState(blockpos$mutableblockpos);

                    float f = (float)l1 + fluidstate.getHeight(this.level(), blockpos$mutableblockpos);
                    this.waterLevel = Math.max((double)f, this.waterLevel);
                    flag |= aabb.minY < (double)f;
                }
            }
        }

        return flag;
    }

    @Nullable
    private AbstractSurfboard.Status isUnderwater() {
        AABB aabb = this.getBoundingBox();
        double d0 = aabb.maxY + 0.001;
        int i = Mth.floor(aabb.minX);
        int j = Mth.ceil(aabb.maxX);
        int k = Mth.floor(aabb.maxY);
        int l = Mth.ceil(d0);
        int i1 = Mth.floor(aabb.minZ);
        int j1 = Mth.ceil(aabb.maxZ);
        boolean flag = false;
        BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();

        for(int k1 = i; k1 < j; ++k1) {
            for(int l1 = k; l1 < l; ++l1) {
                for(int i2 = i1; i2 < j1; ++i2) {
                    blockpos$mutableblockpos.set(k1, l1, i2);
                    FluidState fluidstate = this.level().getFluidState(blockpos$mutableblockpos);
                    if (d0 < (double)((float)blockpos$mutableblockpos.getY() + fluidstate.getHeight(this.level(), blockpos$mutableblockpos))) {
                        if (!fluidstate.isSource()) {
                            return AbstractSurfboard.Status.UNDER_FLOWING_WATER;
                        }

                        flag = true;
                    }
                }
            }
        }

        return flag ? AbstractSurfboard.Status.UNDER_WATER : null;
    }

    private void floatSurfboard() {
        double d0 = (double) -1/50;
        double d1 = this.isNoGravity() ? 0.0 : (double) -1/50;
        double d2 = 0.0;
        this.invFriction = 0.03F;

        if (this.oldStatus == AbstractSurfboard.Status.IN_AIR && this.status != AbstractSurfboard.Status.IN_AIR && this.status != AbstractSurfboard.Status.ON_LAND) {
            this.waterLevel = this.getY(1.0);
            this.setPos(this.getX(), (double)(this.getWaterLevelAbove() - this.getBbHeight() + 0.101), this.getZ());
            this.setDeltaMovement(this.getDeltaMovement().multiply(1.0, 0.0, 1.0));
            this.lastYd = 0.0;
            this.status = AbstractSurfboard.Status.IN_WATER;

        } else {
            if (this.status == AbstractSurfboard.Status.IN_WATER) {
                d2 = (this.waterLevel - this.getY()) / (double)this.getBbHeight();
                this.invFriction = 0.9F;

            } else if (this.status == AbstractSurfboard.Status.UNDER_FLOWING_WATER) {
                d1 = -7.0E-4;
                this.invFriction = 0.9F;

            } else if (this.status == AbstractSurfboard.Status.UNDER_WATER) {
                d2 = (this.waterLevel - this.getY()) / (double)this.getBbHeight();
                this.invFriction = 0.45F;

            } else if (this.status == AbstractSurfboard.Status.IN_AIR) {
                this.invFriction = 0.9F;

            } else if (this.status == AbstractSurfboard.Status.ON_LAND) {
                this.invFriction = this.landFriction;
                if (this.getControllingPassenger() instanceof Player) {
                    this.landFriction /= 2.0F;
                }
            }

            Vec3 vec3 = this.getDeltaMovement();
            this.setDeltaMovement(vec3.x * (double)this.invFriction, vec3.y + d1, vec3.z * (double)this.invFriction);
            this.deltaRotation *= this.invFriction;
            if (d2 > 0.0) {
                Vec3 vec31 = this.getDeltaMovement();
                this.setDeltaMovement(vec31.x, (vec31.y + d2 * 0.06) * 0.75, vec31.z);
            }
        }

    }

    private void setBubbleTime(int pBubbleTime) {
        this.entityData.set(DATA_ID_BUBBLE_TIME, pBubbleTime);
    }

    private int getBubbleTime() {
        return (Integer)this.entityData.get(DATA_ID_BUBBLE_TIME);
    }

    public float getBubbleAngle(float pPartialTicks) {
        return Mth.lerp(pPartialTicks, this.bubbleAngleO, this.bubbleAngle);
    }

    private void controlSurfboard() {
        if (this.isVehicle()) {
            float f = 0.0F;
            if (this.inputLeft) {
                --this.deltaRotation;
            }

            if (this.inputRight) {
                ++this.deltaRotation;
            }

            if (this.inputRight != this.inputLeft && !this.inputUp && !this.inputDown) {
                f += 0.005F;
            }

            this.setYRot(this.getYRot() + this.deltaRotation);
            if (this.inputUp) {
                f += 0.04F;
            }

            if (this.inputDown) {
                f -= 0.005F;
            }

            this.setDeltaMovement(this.getDeltaMovement().add((double)(Mth.sin(-this.getYRot() * 0.017453292F) * f), 0.0, (double)(Mth.cos(this.getYRot() * 0.017453292F) * f)));
        }

    }

    protected float getSinglePassengerXOffset() {
        return 0.0F;
    }

    public boolean hasEnoughSpaceFor(Entity pEntity) {
        return pEntity.getBbWidth() < this.getBbWidth();
    }

    @Override
    protected void positionRider(Entity pPassenger, Entity.MoveFunction pCallback) {
        if (this.hasPassenger(pPassenger)) {
            double xOffset = this.getSinglePassengerXOffset();
            double ridingOffset = (float)((this.isRemoved() ? 0.009999999776482582 : this.getPassengersRidingOffset()) + pPassenger.getMyRidingOffset());

            Vec3 vec3 = (new Vec3(xOffset, 0.0, 0.0)).yRot(-this.getYRot() * 0.017453292F - 1.5707964F);
            pCallback.accept(pPassenger, this.getX() + vec3.x, this.getY() + ridingOffset, this.getZ() + vec3.z);

            // Y rotation
            pPassenger.setYRot(pPassenger.getYRot() + this.deltaRotation);
            pPassenger.setYHeadRot(pPassenger.getYHeadRot() + this.deltaRotation);

            // lock this position/rotation while on surfboard
            this.clampRotation(pPassenger);
        }

    }
    @Override
    public @NotNull Vec3 getDismountLocationForPassenger(LivingEntity pLivingEntity) {
        Vec3 vec3 = getCollisionHorizontalEscapeVector((double)(this.getBbWidth() * Mth.SQRT_OF_TWO), (double)pLivingEntity.getBbWidth(), pLivingEntity.getYRot());
        double d0 = this.getX() + vec3.x;
        double d1 = this.getZ() + vec3.z;
        BlockPos blockpos = BlockPos.containing(d0, this.getBoundingBox().maxY, d1);
        BlockPos blockpos1 = blockpos.below();
        if (!this.level().isWaterAt(blockpos1)) {
            List<Vec3> list = Lists.newArrayList();
            double d2 = this.level().getBlockFloorHeight(blockpos);
            if (DismountHelper.isBlockFloorValid(d2)) {
                list.add(new Vec3(d0, (double)blockpos.getY() + d2, d1));
            }

            double d3 = this.level().getBlockFloorHeight(blockpos1);
            if (DismountHelper.isBlockFloorValid(d3)) {
                list.add(new Vec3(d0, (double)blockpos1.getY() + d3, d1));
            }

            UnmodifiableIterator var14 = pLivingEntity.getDismountPoses().iterator();

            while (var14.hasNext()) {
                Pose pose = (Pose)var14.next();
                Iterator var16 = list.iterator();

                while(var16.hasNext()) {
                    Vec3 vec31 = (Vec3)var16.next();
                    if (DismountHelper.canDismountTo(this.level(), vec31, pLivingEntity, pose)) {
                        pLivingEntity.setPose(pose);
                        return vec31;
                    }
                }
            }
        }

        return super.getDismountLocationForPassenger(pLivingEntity);
    }

    protected void clampRotation(Entity pEntityToUpdate) {
        pEntityToUpdate.setYBodyRot(this.getYRot());
        float f = Mth.wrapDegrees(pEntityToUpdate.getYRot() - this.getYRot());
        float f1 = Mth.clamp(f, -105.0F, 105.0F);
        pEntityToUpdate.yRotO += f1 - f;
        pEntityToUpdate.setYRot(pEntityToUpdate.getYRot() + f1 - f);
        pEntityToUpdate.setYHeadRot(pEntityToUpdate.getYRot());
    }

    @Override
    public void onPassengerTurned(Entity pEntityToUpdate) {
        this.clampRotation(pEntityToUpdate);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag pCompound) {
        pCompound.putString("Type", this.getModVariant().getSerializedName());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag pCompound) {
        if (pCompound.contains("Type", 8)) {
            this.setVariant(SurfboardEntity.Type.byName(pCompound.getString("Type")));
        }
    }

    @Override
    public @NotNull InteractionResult interact(Player pPlayer, @NotNull InteractionHand pHand) {
        if (pPlayer.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        } else if (this.outOfControlTicks < 60.0F) {
            if (!this.level().isClientSide) {

                return pPlayer.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;

            } else {
                return InteractionResult.SUCCESS;
            }
        } else {
            return InteractionResult.PASS;
        }
    }

    @Override
    protected void checkFallDamage(double pY, boolean pOnGround, BlockState pState, BlockPos pPos) {
        this.lastYd = this.getDeltaMovement().y;
        if (!this.isPassenger()) {
            if (pOnGround) {
                if (this.fallDistance > 3.0F) {
                    if (this.status != AbstractSurfboard.Status.ON_LAND) {
                        this.resetFallDistance();
                        return;
                    }

                    this.causeFallDamage(this.fallDistance, 1.0F, this.damageSources().fall());
                    if (!this.level().isClientSide && !this.isRemoved()) {
                        this.kill();
                        if (this.level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
                            int j;
                            for(j = 0; j < 3; ++j) {
                                this.spawnAtLocation(this.getVariant().getPlanks());
                            }

                            for(j = 0; j < 2; ++j) {
                                this.spawnAtLocation(Items.STICK);
                            }
                        }
                    }
                }

                this.resetFallDistance();
            } else if (pY < 0.0) {
                this.fallDistance -= (float)pY;
            }
        }

    }

    public void setDamage(float pDamageTaken) {
        this.entityData.set(DATA_ID_DAMAGE, pDamageTaken);
    }

    public float getDamage() {
        return (Float)this.entityData.get(DATA_ID_DAMAGE);
    }

    public void setHurtTime(int pHurtTime) {
        this.entityData.set(DATA_ID_HURT, pHurtTime);
    }

    public int getHurtTime() {
        return (Integer)this.entityData.get(DATA_ID_HURT);
    }

    public boolean isPaddling() {
        return (Boolean)this.entityData.get(DATA_ID_PADDLING);
    }

    private void setPaddling(boolean paddling) {
        this.entityData.set(DATA_ID_PADDLING, paddling);
    }

    public void setHurtDir(int pHurtDirection) {
        this.entityData.set(DATA_ID_HURTDIR, pHurtDirection);
    }

    public int getHurtDir() {
        return (Integer)this.entityData.get(DATA_ID_HURTDIR);
    }

    public void setVariant(SurfboardEntity.Type pVariant) {
        this.entityData.set(DATA_ID_TYPE, pVariant.ordinal());
    }

    public Type getVariant() {
        return SurfboardEntity.Type.byId((Integer)this.entityData.get(DATA_ID_TYPE));
    }

    protected boolean canAddPassenger(Entity pPassenger) {
        return this.getPassengers().size() < this.getMaxPassengers();
    }

    protected int getMaxPassengers() {
        return 1;
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        Entity entity = this.getFirstPassenger();
        LivingEntity livingentity1;

        if (entity instanceof LivingEntity livingentity) {
            livingentity1 = livingentity;
        } else {
            livingentity1 = null;
        }

        return livingentity1;
    }

    @Override
    public boolean isUnderWater() {
        return this.status == AbstractSurfboard.Status.UNDER_WATER || this.status == AbstractSurfboard.Status.UNDER_FLOWING_WATER;
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (this.isControlledByLocalInstance() && this.lerpSteps > 0) {
            this.lerpSteps = 0;
            this.absMoveTo(this.lerpX, this.lerpY, this.lerpZ, (float)this.lerpYRot, (float)this.lerpXRot);
        }

    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(this.getDropItem());
    }

    public SurfboardEntity.Type getModVariant() {
        return SurfboardEntity.Type.byId(this.entityData.get(DATA_ID_TYPE));
    }

    static {
        DATA_ID_HURT = SynchedEntityData.defineId(AbstractSurfboard.class, EntityDataSerializers.INT);
        DATA_ID_HURTDIR = SynchedEntityData.defineId(AbstractSurfboard.class, EntityDataSerializers.INT);
        DATA_ID_DAMAGE = SynchedEntityData.defineId(AbstractSurfboard.class, EntityDataSerializers.FLOAT);
        DATA_ID_TYPE = SynchedEntityData.defineId(AbstractSurfboard.class, EntityDataSerializers.INT);
        DATA_ID_BUBBLE_TIME = SynchedEntityData.defineId(AbstractSurfboard.class, EntityDataSerializers.INT);
        DATA_ID_PADDLING = SynchedEntityData.defineId(AbstractSurfboard.class, EntityDataSerializers.BOOLEAN);
    }


    public static enum Type implements StringRepresentable {
        OAK(Blocks.OAK_PLANKS, "oak");

        private final String name;
        private final Block planks;
        public static final StringRepresentable.EnumCodec<SurfboardEntity.Type> CODEC = StringRepresentable.fromEnum(SurfboardEntity.Type::values);
        private static final IntFunction<Type> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);

        private Type(Block pPlanks, String pName) {
            this.name = pName;
            this.planks = pPlanks;
        }

        public String getSerializedName() {
            return this.name;
        }

        public String getName() {
            return this.name;
        }

        public Block getPlanks() {
            return this.planks;
        }

        public String toString() {
            return this.name;
        }

        /**
         * Get a surfboard type by its enum ordinal
         */
        public static SurfboardEntity.Type byId(int pId) {
            return BY_ID.apply(pId);
        }

        public static SurfboardEntity.Type byName(String pName) {
            return CODEC.byName(pName, OAK);
        }
    }


    public static enum Status {
        IN_WATER,
        UNDER_WATER,
        UNDER_FLOWING_WATER,
        ON_LAND,
        IN_AIR;

        private Status() {
        }
    }

}
