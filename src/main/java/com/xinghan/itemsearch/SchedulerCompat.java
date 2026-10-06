package com.xinghan.itemsearch;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Optional Folia APIs are reflected so old CraftBukkit can load the same classes. */
public final class SchedulerCompat {
    private final Plugin plugin;
    private final boolean folia;
    public SchedulerCompat(Plugin plugin) {
        this.plugin = plugin;
        boolean detected;
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, Bukkit.getServer().getClass().getClassLoader()); detected = true; }
        catch (ClassNotFoundException absent) { detected = false; }
        folia = detected;
    }
    public boolean isFolia() { return folia; }
    public void global(Runnable task) {
        if (!folia) { if (Bukkit.isPrimaryThread()) task.run(); else Bukkit.getScheduler().runTask(plugin, task); return; }
        if (ownedGlobal()) { task.run(); return; }
        invoke(scheduler("getGlobalRegionScheduler"), "execute", new Class<?>[]{Plugin.class, Runnable.class}, plugin, task);
    }
    public void globalLater(long ticks, Runnable task) {
        if (!folia) { Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1L,ticks)); return; }
        Consumer<Object> consumer = ignored -> task.run();
        invoke(scheduler("getGlobalRegionScheduler"), "runDelayed", new Class<?>[]{Plugin.class, Consumer.class, long.class}, plugin, consumer, Math.max(1L,ticks));
    }
    public void entity(Player player, Runnable task) { entity(player,task,() -> { }); }
    /** Retirement runs once if a player disappeared before the task could execute. */
    public boolean entity(Player player, Runnable task, Runnable retired) { return entityDelay(player,0,task,retired); }
    public void entityLater(Player player, long ticks, Runnable task) { entityDelay(player,Math.max(1L,ticks),task,() -> { }); }
    public boolean entityLater(Player player, long ticks, Runnable task, Runnable retired) { return entityDelay(player,Math.max(1L,ticks),task,retired); }
    private boolean entityDelay(Player player, long delay, Runnable task, Runnable retired) {
        AtomicBoolean dispatched = new AtomicBoolean();
        Runnable run = () -> { if (dispatched.compareAndSet(false,true)) task.run(); };
        Runnable gone = () -> { if (dispatched.compareAndSet(false,true)) retired.run(); };
        if (!folia) {
            Runnable checked = () -> { if (player.isOnline()) run.run(); else gone.run(); };
            if (delay == 0 && Bukkit.isPrimaryThread()) checked.run();
            else Bukkit.getScheduler().runTaskLater(plugin,checked,Math.max(1L,delay));
            return true;
        }
        if (delay == 0 && owns(player)) { if (player.isOnline()) run.run(); else gone.run(); return true; }
        try {
            Object scheduler = invoke(player,"getScheduler",new Class<?>[0]);
            Object accepted = invoke(scheduler,"execute",new Class<?>[]{Plugin.class,Runnable.class,Runnable.class,long.class},plugin,run,gone,Math.max(1L,delay));
            if (Boolean.FALSE.equals(accepted)) { gone.run(); return false; }
            return true;
        } catch (RuntimeException error) { gone.run(); plugin.getLogger().warning("Could not schedule player task: " + error.getMessage()); return false; }
    }
    /** Only releases staged menus during disable; native tasks outlive Bukkit's disabled-plugin wrapper. */
    public boolean entityCleanup(Player player, Runnable task, Runnable retired) {
        if (!folia || owns(player)) { if(player.isOnline()) task.run(); else retired.run(); return true; }
        AtomicBoolean dispatched = new AtomicBoolean();
        Consumer<Object> run = ignored -> { if(dispatched.compareAndSet(false,true)) task.run(); };
        Consumer<Object> gone = ignored -> { if(dispatched.compareAndSet(false,true)) retired.run(); };
        try {
            Object nativeScheduler=player.getClass().getField("taskScheduler").get(player);
            Method schedule=nativeScheduler.getClass().getMethod("schedule",Consumer.class,Consumer.class,long.class);
            boolean accepted=Boolean.TRUE.equals(schedule.invoke(nativeScheduler,run,gone,1L));
            if(!accepted) gone.accept(null);
            return accepted;
        } catch(ReflectiveOperationException error) {
            plugin.getLogger().severe("Cannot close a staged menu on its Folia entity thread: " + error);
            gone.accept(null); return false;
        }
    }
    public boolean supportsEntityCleanup(Player player) {
        if(!folia) return true;
        try {
            Object scheduler=player.getClass().getField("taskScheduler").get(player);
            scheduler.getClass().getMethod("schedule",Consumer.class,Consumer.class,long.class); return true;
        } catch(ReflectiveOperationException unavailable) { return false; }
    }
    public void region(World world, int chunkX, int chunkZ, Runnable task) {
        if (!folia) { global(task); return; }
        invoke(scheduler("getRegionScheduler"),"execute",new Class<?>[]{Plugin.class,World.class,int.class,int.class,Runnable.class},plugin,world,chunkX,chunkZ,task);
    }
    public boolean owns(Player player) {
        if (!folia) return Bukkit.isPrimaryThread();
        try { return Boolean.TRUE.equals(Bukkit.class.getMethod("isOwnedByCurrentRegion",org.bukkit.entity.Entity.class).invoke(null,player)); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Folia entity ownership API unavailable",error); }
    }
    private boolean ownedGlobal() {
        try { return Boolean.TRUE.equals(Bukkit.class.getMethod("isGlobalTickThread").invoke(null)); }
        catch (ReflectiveOperationException ignored) { return false; }
    }
    private Object scheduler(String name) { return invoke(Bukkit.getServer(),name,new Class<?>[0]); }
    static Object invoke(Object target, String name, Class<?>[] signature, Object... arguments) {
        try {
            Method method = target.getClass().getMethod(name,signature);
            if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) method.setAccessible(true);
            return method.invoke(target,arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(name + " failed",cause);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Required scheduler API " + name + " unavailable",error); }
    }
}

