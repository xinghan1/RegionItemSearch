package com.xinghan.itemsearch;

import com.xinghan.itemsearch.scan.WorldScanner;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Search never loads chunks; explicit result teleports use the destination region scheduler. */
final class ResultTeleport {
    private static final Set<String> HAZARDS=Compat.set("LAVA","STATIONARY_LAVA","FIRE","SOUL_FIRE","CACTUS","MAGMA","MAGMA_BLOCK","CAMPFIRE","SOUL_CAMPFIRE","POWDER_SNOW","SWEET_BERRY_BUSH","WITHER_ROSE");
    private final RegionItemSearchPlugin plugin;
    private final Set<UUID> pending=Collections.newSetFromMap(new ConcurrentHashMap<UUID,Boolean>());
    ResultTeleport(RegionItemSearchPlugin plugin) { this.plugin=plugin; }
    void teleport(Player player,WorldScanner.Hit hit,UUID worldId,BooleanSupplier current) {
        plugin.scheduler().entity(player,() -> begin(player,hit,worldId,current),() -> pending.remove(player.getUniqueId()));
    }
    private void begin(Player player,WorldScanner.Hit hit,UUID worldId,BooleanSupplier current) {
        World world=worldId==null?null:Bukkit.getWorld(worldId);
        if(world==null) { tell(player,"teleport.world-unavailable");return; }
        Location center=new Location(world,hit.x()+0.5,hit.y()+1.0,hit.z()+0.5);
        if(Math.abs((long)hit.x())>29_999_984 || Math.abs((long)hit.z())>29_999_984 || !inside(world,center)) { tell(player,"teleport.outside-border");return; }
        UUID id=player.getUniqueId();
        if(!pending.add(id)) { tell(player,"teleport.pending");return; }
        tell(player,"teleport.preparing");
        final Location facing=player.getLocation().clone();
        try {
            Method async;
            try { async=world.getClass().getMethod("getChunkAtAsync",int.class,int.class,boolean.class); }
            catch(NoSuchMethodException legacy) { async=null; }
            if(async!=null) {
                @SuppressWarnings("unchecked") CompletableFuture<Chunk> chunk=(CompletableFuture<Chunk>)async.invoke(world,hit.x()>>4,hit.z()>>4,false);
                chunk.whenComplete((loaded,error) -> {
                    if(error!=null || loaded==null) fail(player,"teleport.chunk-unavailable");
                    else plugin.scheduler().region(world,hit.x()>>4,hit.z()>>4,() -> inspect(player,world,loaded,hit,facing,current));
                });
            } else plugin.scheduler().region(world,hit.x()>>4,hit.z()>>4,() -> {
                if(!world.loadChunk(hit.x()>>4,hit.z()>>4,false)) { fail(player,"teleport.chunk-unavailable");return; }
                inspect(player,world,world.getChunkAt(hit.x()>>4,hit.z()>>4),hit,facing,current);
            });
        } catch(ReflectiveOperationException | RuntimeException error) {
            pending.remove(id); plugin.message(player,Language.component("teleport.read-failed","error",Component.text(String.valueOf(error.getMessage()))));
        }
    }
    private void inspect(Player player,World world,Chunk chunk,WorldScanner.Hit hit,Location facing,BooleanSupplier current) {
        try {
            if(!chunk.isLoaded() || Bukkit.getWorld(world.getUID())!=world) { fail(player,"teleport.chunk-unavailable");return; }
            Location destination=landing(world,chunk,hit,facing);
            if(destination==null) { fail(player,"teleport.no-landing");return; }
            plugin.scheduler().entity(player,() -> finish(player,hit,destination,current),() -> pending.remove(player.getUniqueId()));
        } catch(RuntimeException error) { pending.remove(player.getUniqueId()); plugin.message(player,Language.component("teleport.failed","error",error.getMessage())); }
    }
    private void finish(Player player,WorldScanner.Hit hit,Location destination,BooleanSupplier current) {
        if(!player.isOnline() || !player.hasPermission("itemsearch.admin") || !current.getAsBoolean()) { fail(player,"teleport.stale");return; }
        try {
            Method async;
            try { async=player.getClass().getMethod("teleportAsync",Location.class,PlayerTeleportEvent.TeleportCause.class); }
            catch(NoSuchMethodException legacy) { async=null; }
            if(async!=null) {
                @SuppressWarnings("unchecked") CompletableFuture<Boolean> result=(CompletableFuture<Boolean>)async.invoke(player,destination,PlayerTeleportEvent.TeleportCause.COMMAND);
                result.whenComplete((success,error) -> complete(player,hit,error==null && Boolean.TRUE.equals(success)));
            } else {
                if(plugin.scheduler().isFolia()) { fail(player,"teleport.incomplete");return; }
                complete(player,hit,player.teleport(destination,PlayerTeleportEvent.TeleportCause.COMMAND));
            }
        } catch(ReflectiveOperationException | RuntimeException error) { pending.remove(player.getUniqueId()); plugin.message(player,Language.component("teleport.failed","error",String.valueOf(error.getMessage()))); }
    }
    private void complete(Player player,WorldScanner.Hit hit,boolean success) {
        pending.remove(player.getUniqueId());
        plugin.message(player,success?Language.component("teleport.success","world",Component.text(hit.world()),"x",hit.x(),"y",hit.y(),"z",hit.z()):Language.component("teleport.incomplete"));
    }
    private void fail(Player player,String key) { pending.remove(player.getUniqueId());tell(player,key); }
    private void tell(Player player,String key) { plugin.message(player,Language.component(key)); }
    private static boolean inside(World world,Location location) {
        Location center=world.getWorldBorder().getCenter(); double half=world.getWorldBorder().getSize()/2;
        return location.getX()>=center.getX()-half && location.getX()<center.getX()+half && location.getZ()>=center.getZ()-half && location.getZ()<center.getZ()+half;
    }
    private static boolean passable(Block block) {
        try { return Boolean.TRUE.equals(block.getClass().getMethod("isPassable").invoke(block)); }
        catch(NoSuchMethodException legacy) { return !block.getType().isSolid(); }
        catch(ReflectiveOperationException error) { return false; }
    }
    private static int minHeight(World world) {
        try { return ((Number)world.getClass().getMethod("getMinHeight").invoke(world)).intValue(); }
        catch(ReflectiveOperationException legacy) { return 0; }
    }
    private static Location landing(World world,Chunk chunk,WorldScanner.Hit hit,Location facing) {
        int[] heights={1,2,0,3,-1,4,-2,5,-3}; int min=minHeight(world);
        for(int radius=0;radius<=2;radius++) for(int offset:heights) {
            long yValue=(long)hit.y()+offset; if(yValue<=min || yValue+1>=world.getMaxHeight()) continue;
            int y=(int)yValue;
            for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
                if(Math.max(Math.abs(dx),Math.abs(dz))!=radius) continue;
                int x=hit.x()+dx,z=hit.z()+dz; if((x>>4)!=chunk.getX()||(z>>4)!=chunk.getZ()) continue;
                Block feet=world.getBlockAt(x,y,z),head=world.getBlockAt(x,y+1,z),floor=world.getBlockAt(x,y-1,z);
                if(!passable(feet)||!passable(head)||feet.isLiquid()||head.isLiquid()||!floor.getType().isSolid()
                    ||HAZARDS.contains(feet.getType().name())||HAZARDS.contains(head.getType().name())||HAZARDS.contains(floor.getType().name())) continue;
                Location result=new Location(world,x+0.5,y,z+0.5,facing.getYaw(),facing.getPitch()); if(inside(world,result)) return result;
            }
        }
        return null;
    }
}
