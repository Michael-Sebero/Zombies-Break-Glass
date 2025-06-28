package michaelsebero.zombiesbreakglass;

import net.minecraft.block.Block;
import net.minecraft.block.BlockGlass;
import net.minecraft.block.BlockPane;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.BlockStainedGlassPane;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Mod(modid = ZombiesBreakGlass.MODID, version = ZombiesBreakGlass.VERSION, name = ZombiesBreakGlass.NAME)
public class ZombiesBreakGlass {
    public static final String MODID = "zombiesbreakglass";
    public static final String VERSION = "1.0";
    public static final String NAME = "Zombies Break Glass";

    // Track glass breaking progress
    private static final Map<String, GlassBreakingData> glassBreakingMap = new HashMap<>();
    
    // Configuration
    private static final int BREAK_TIME_TICKS = 60; // 3 seconds at 20 ticks per second
    private static final double DETECTION_RANGE = 16.0;
    private static final double GLASS_BREAK_RANGE = 4.0;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Initialization code if needed
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        // Add glass breaking AI to zombies when they spawn
        if (event.getEntity() instanceof EntityZombie && !event.getWorld().isRemote) {
            EntityZombie zombie = (EntityZombie) event.getEntity();
            zombie.tasks.addTask(2, new EntityAIBreakGlass(zombie));
        }
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        // Only process on server side and during END phase
        if (event.phase != TickEvent.Phase.END || event.world.isRemote) {
            return;
        }

        World world = event.world;

        // Update glass breaking progress
        updateGlassBreaking(world);
    }

    public static boolean isGlassBlock(Block block) {
        return block instanceof BlockGlass || 
               block instanceof BlockPane ||
               block instanceof BlockStainedGlass ||
               block instanceof BlockStainedGlassPane ||
               block == Blocks.GLASS ||
               block == Blocks.GLASS_PANE ||
               block == Blocks.STAINED_GLASS ||
               block == Blocks.STAINED_GLASS_PANE;
    }

    public static boolean canSeePlayerThroughGlass(EntityZombie zombie, EntityPlayer player) {
        World world = zombie.world;
        Vec3d zombiePos = zombie.getPositionEyes(1.0F);
        Vec3d playerPos = player.getPositionEyes(1.0F);

        // Check if there's a direct line of sight, ignoring glass blocks
        return hasLineOfSightIgnoringGlass(world, zombiePos, playerPos);
    }

    private static boolean hasLineOfSightIgnoringGlass(World world, Vec3d start, Vec3d end) {
        Vec3d direction = end.subtract(start).normalize();
        double distance = start.distanceTo(end);
        
        for (double d = 0.5; d < distance; d += 0.5) {
            Vec3d checkPos = start.add(direction.scale(d));
            BlockPos blockPos = new BlockPos(checkPos);
            IBlockState state = world.getBlockState(blockPos);
            Block block = state.getBlock();
            
            // If we hit a non-glass, non-air block, line of sight is blocked
            if (!block.isAir(state, world, blockPos) && !isGlassBlock(block)) {
                AxisAlignedBB blockBB = state.getCollisionBoundingBox(world, blockPos);
                if (blockBB != null && !blockBB.equals(Block.NULL_AABB)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void startBreakingGlass(World world, BlockPos pos, EntityZombie zombie) {
        String key = pos.toString();
        
        if (!glassBreakingMap.containsKey(key) && isGlassBlock(world.getBlockState(pos).getBlock())) {
            glassBreakingMap.put(key, new GlassBreakingData(pos, world.getTotalWorldTime(), zombie));
            
            // Play glass hitting sound
            world.playSound(null, pos, SoundEvents.BLOCK_GLASS_HIT, SoundCategory.BLOCKS, 1.0F, 1.0F);
        }
    }

    private void updateGlassBreaking(World world) {
        Iterator<Map.Entry<String, GlassBreakingData>> iterator = glassBreakingMap.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<String, GlassBreakingData> entry = iterator.next();
            GlassBreakingData data = entry.getValue();
            
            long currentTime = world.getTotalWorldTime();
            long elapsed = currentTime - data.startTime;
            
            // Create breaking particles
            if (elapsed % 10 == 0) {
                BlockPos pos = data.pos;
                IBlockState state = world.getBlockState(pos);
                if (isGlassBlock(state.getBlock())) {
                    world.spawnParticle(EnumParticleTypes.BLOCK_CRACK, 
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        0, 0, 0, 
                        Block.getStateId(state));
                }
            }
            
            // Break the glass after the delay
            if (elapsed >= BREAK_TIME_TICKS) {
                BlockPos pos = data.pos;
                IBlockState state = world.getBlockState(pos);
                
                if (isGlassBlock(state.getBlock())) {
                    // Play breaking sound
                    world.playSound(null, pos, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1.0F, 1.0F);
                    
                    // Break the block
                    world.destroyBlock(pos, true);
                }
                
                iterator.remove();
            }
        }
    }

    // Custom AI task for breaking glass
    public static class EntityAIBreakGlass extends EntityAIBase {
        private final EntityZombie zombie;
        private EntityPlayer targetPlayer;
        private BlockPos targetGlass;
        private int breakingTicks;

        public EntityAIBreakGlass(EntityZombie zombie) {
            this.zombie = zombie;
            this.setMutexBits(1); // Conflicts with movement tasks
        }

        @Override
        public boolean shouldExecute() {
            // Find nearby players
            List<EntityPlayer> nearbyPlayers = zombie.world.getEntitiesWithinAABB(
                EntityPlayer.class, 
                zombie.getEntityBoundingBox().grow(DETECTION_RANGE)
            );

            if (nearbyPlayers.isEmpty()) {
                return false;
            }

            this.targetPlayer = nearbyPlayers.get(0);
            
            // Check if zombie can see player through glass
            if (canSeePlayerThroughGlass(zombie, targetPlayer)) {
                this.targetGlass = findGlassToBreak();
                return this.targetGlass != null;
            }

            return false;
        }

        @Override
        public boolean shouldContinueExecuting() {
            if (targetPlayer == null || targetGlass == null) {
                return false;
            }

            // Stop if player is too far or glass is already broken
            if (zombie.getDistanceSq(targetPlayer) > DETECTION_RANGE * DETECTION_RANGE) {
                return false;
            }

            if (!isGlassBlock(zombie.world.getBlockState(targetGlass).getBlock())) {
                return false;
            }

            return canSeePlayerThroughGlass(zombie, targetPlayer);
        }

        @Override
        public void startExecuting() {
            this.breakingTicks = 0;
        }

        @Override
        public void updateTask() {
            if (targetGlass == null) return;

            // Move towards the glass
            double distanceToGlass = zombie.getDistanceSq(targetGlass);
            
            if (distanceToGlass > 4.0) {
                // Navigate to the glass
                PathNavigate navigator = zombie.getNavigator();
                navigator.tryMoveToXYZ(targetGlass.getX() + 0.5, targetGlass.getY(), targetGlass.getZ() + 0.5, 1.0);
            } else {
                // Close enough to start breaking
                zombie.getNavigator().clearPath();
                
                // Look at the glass
                zombie.getLookHelper().setLookPosition(
                    targetGlass.getX() + 0.5, 
                    targetGlass.getY() + 0.5, 
                    targetGlass.getZ() + 0.5, 
                    30.0F, 30.0F
                );

                // Start breaking the glass and glass above it
                startBreakingGlass(zombie.world, targetGlass, zombie);
                
                // Also break glass above to create opening
                BlockPos abovePos = targetGlass.up();
                if (isGlassBlock(zombie.world.getBlockState(abovePos).getBlock())) {
                    startBreakingGlass(zombie.world, abovePos, zombie);
                }
            }
        }

        @Override
        public void resetTask() {
            this.targetPlayer = null;
            this.targetGlass = null;
            this.breakingTicks = 0;
        }

        private BlockPos findGlassToBreak() {
            Vec3d zombiePos = zombie.getPositionEyes(1.0F);
            Vec3d playerPos = targetPlayer.getPositionEyes(1.0F);
            
            Vec3d direction = playerPos.subtract(zombiePos).normalize();
            double distance = Math.min(zombiePos.distanceTo(playerPos), GLASS_BREAK_RANGE);

            // Find the closest glass block between zombie and player
            for (double d = 1.0; d < distance; d += 0.5) {
                Vec3d checkPos = zombiePos.add(direction.scale(d));
                BlockPos blockPos = new BlockPos(checkPos);
                
                IBlockState state = zombie.world.getBlockState(blockPos);
                Block block = state.getBlock();

                if (isGlassBlock(block)) {
                    return blockPos;
                }
            }

            return null;
        }
    }

    private static class GlassBreakingData {
        public final BlockPos pos;
        public final long startTime;
        public final EntityZombie zombie;

        public GlassBreakingData(BlockPos pos, long startTime, EntityZombie zombie) {
            this.pos = pos;
            this.startTime = startTime;
            this.zombie = zombie;
        }
    }
}
