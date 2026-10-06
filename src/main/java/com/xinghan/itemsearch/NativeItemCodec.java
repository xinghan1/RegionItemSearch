package com.xinghan.itemsearch;

import com.xinghan.itemsearch.nbt.NbtReader;
import com.xinghan.itemsearch.nbt.NbtWriter;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Native item NBT, including unknown tags/components; never Bukkit YAML serialization. */
final class NativeItemCodec {
    private static final int MAX_BYTES = 67_108_864;
    private static final int MAX_DEPTH = 256;
    private final Class<?> itemType, compoundType;
    private final Method nmsCopy, bukkitCopy, writeCompressed, readCompressed;
    private final Method save, parse;
    private final Constructor<?> itemConstructor;
    private final Object codec, ops;
    private final Class<?> limiterType;
    NativeItemCodec() {
        try {
            ClassLoader loader=Bukkit.getServer().getClass().getClassLoader();
            String craft=Bukkit.getServer().getClass().getPackage().getName();
            String suffix=craft.substring("org.bukkit.craftbukkit".length());
            String old="net.minecraft.server"+suffix+".";
            Class<?> craftItem=Class.forName(craft+".inventory.CraftItemStack",false,loader);
            nmsCopy=craftItem.getMethod("asNMSCopy",ItemStack.class); itemType=nmsCopy.getReturnType();
            bukkitCopy=bukkitCopy(craftItem,itemType);
            compoundType=load(loader,"net.minecraft.nbt.CompoundTag","net.minecraft.nbt.NBTTagCompound",old+"NBTTagCompound");
            Class<?> io=load(loader,"net.minecraft.nbt.NbtIo","net.minecraft.nbt.NBTCompressedStreamTools",old+"NBTCompressedStreamTools");
            Method writer=null,reader=null;
            for(Method method:io.getMethods()) {
                Class<?>[] p=method.getParameterTypes();
                if(!Modifier.isStatic(method.getModifiers())) continue;
                if(p.length==2 && p[0]==compoundType && p[1]==OutputStream.class) writer=method;
                if((p.length==1||p.length==2) && p[0]==InputStream.class && method.getReturnType()==compoundType) reader=method;
            }
            if(writer==null||reader==null) throw new NoSuchMethodException("Compressed NBT I/O");
            writeCompressed=writer; readCompressed=reader;
            limiterType=reader.getParameterTypes().length==2 ? reader.getParameterTypes()[1] : null;
            Method oldSave=null,oldParse=null;
            for(Method method:itemType.getMethods()) {
                Class<?>[] p=method.getParameterTypes();
                if(p.length==1 && p[0]==compoundType) {
                    if(!Modifier.isStatic(method.getModifiers()) && compoundType.isAssignableFrom(method.getReturnType())) oldSave=method;
                    if(Modifier.isStatic(method.getModifiers()) && method.getReturnType()==itemType) oldParse=method;
                }
            }
            Constructor<?> oldConstructor=null;
            try { oldConstructor=itemType.getConstructor(compoundType); } catch(NoSuchMethodException absent) { }
            if(oldSave!=null && (oldParse!=null || oldConstructor!=null)) { save=oldSave;parse=oldParse;itemConstructor=oldConstructor;codec=null;ops=null; }
            else {
                save=null; parse=null; itemConstructor=null;
                Class<?> codecType=Class.forName("com.mojang.serialization.Codec",false,loader);
                Object found=null;
                try { Field field=itemType.getField("CODEC"); if(codecType.isAssignableFrom(field.getType())) found=field.get(null); } catch(NoSuchFieldException absent) { }
                if(found==null) for(Field field:itemType.getFields()) if(Modifier.isStatic(field.getModifiers()) && field.getType()==codecType) { found=field.get(null);break; }
                if(found==null) throw new NoSuchFieldException("ItemStack.CODEC");
                codec=found;
                Class<?> nbtOpsType=load(loader,"net.minecraft.nbt.NbtOps","net.minecraft.nbt.DynamicOpsNBT");
                Object nbtOps=null;
                for(Field field:nbtOpsType.getFields()) if(Modifier.isStatic(field.getModifiers()) && field.getType()==nbtOpsType) { nbtOps=field.get(null);break; }
                Class<?> registryOps=Class.forName("net.minecraft.resources.RegistryOps",false,loader);
                Object nativeServer=Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
                Object registry=null;
                Method factory=null;
                for(Method method:registryOps.getMethods()) {
                    Class<?>[] p=method.getParameterTypes();
                    if(!Modifier.isStatic(method.getModifiers()) || p.length!=2 || method.getReturnType()!=registryOps || nbtOps==null || !p[0].isInstance(nbtOps)) continue;
                    Object candidate=registry(nativeServer,p[1]);
                    if(candidate!=null) { registry=candidate;factory=method;break; }
                }
                if(factory==null) throw new NoSuchMethodException("RegistryOps.create with server registry access");
                ops=factory.invoke(null,nbtOps,registry);
            }
        } catch(ReflectiveOperationException error) { throw BukkitCompat.failure("initialize native NBT item adapter",error); }
    }
    byte[] serialize(ItemStack item) {
        try {
            Object nativeItem=nmsCopy.invoke(null,item.clone());
            Object compound;
            if(save!=null) compound=save.invoke(nativeItem,compoundType.getDeclaredConstructor().newInstance());
            else compound=codecResult("encodeStart",nativeItem);
            if(!compoundType.isInstance(compound)) throw new IllegalArgumentException("Item codec did not return an NBT compound");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); writeCompressed.invoke(null,compound,bytes);
            Map<String,Object> root=new LinkedHashMap<String,Object>(NbtReader.readAuto(bytes.toByteArray(),MAX_BYTES,MAX_DEPTH));
            int version=BukkitCompat.dataVersion(); if(version>0) root.put("DataVersion",version);
            return compress(root);
        } catch(ReflectiveOperationException error) { throw BukkitCompat.failure("encode native item NBT",error); }
        catch(IOException error) { throw new IllegalStateException("Invalid encoded item NBT",error); }
    }
    ItemStack deserialize(byte[] bytes) {
        try {
            Map<String,Object> root=new LinkedHashMap<String,Object>(NbtReader.readAuto(bytes,MAX_BYTES,MAX_DEPTH));
            Object version=root.remove("DataVersion");
            if(version instanceof Number && ((Number)version).intValue()>BukkitCompat.dataVersion())
                throw new IllegalArgumentException("Refusing to decode item data from a newer server version");
            byte[] encoded=compress(root);
            Object compound=readCompressed.getParameterTypes().length==1 ? readCompressed.invoke(null,new ByteArrayInputStream(encoded))
                    : readCompressed.invoke(null,new ByteArrayInputStream(encoded),limiter(limiterType));
            Object nativeItem=parse!=null ? parse.invoke(null,compound) : itemConstructor!=null ? itemConstructor.newInstance(compound) : codecResult("parse",compound);
            ItemStack item=(ItemStack)bukkitCopy.invoke(null,nativeItem);
            if(BukkitCompat.air(item)) throw new IllegalArgumentException("Native item decoder returned an empty item");
            return item;
        } catch(ReflectiveOperationException error) { throw BukkitCompat.failure("decode native item NBT",error); }
        catch(IOException error) { throw new IllegalStateException("Invalid item NBT",error); }
    }
    private Object codecResult(String name,Object input) throws ReflectiveOperationException {
        Class<?> codecType=Class.forName("com.mojang.serialization.Codec",false,itemType.getClassLoader());
        Class<?> dynamicOps=Class.forName("com.mojang.serialization.DynamicOps",false,itemType.getClassLoader());
        Object result=codecType.getMethod(name,dynamicOps,Object.class).invoke(codec,ops,input);
        Class<?> resultType=Class.forName("com.mojang.serialization.DataResult",false,itemType.getClassLoader());
        Optional<?> value=(Optional<?>)resultType.getMethod("result").invoke(result);
        if(!value.isPresent()) throw new IllegalArgumentException("Item NBT codec rejected data: " + result);
        return value.get();
    }
    private static Object registry(Object server,Class<?> required) throws ReflectiveOperationException {
        if(required.isInstance(server)) return server;
        for(Method method:server.getClass().getMethods())
            if(!Modifier.isStatic(method.getModifiers()) && method.getParameterTypes().length==0 && required.isAssignableFrom(method.getReturnType())) {
                Object value=method.invoke(server); if(value!=null) return value;
            }
        return null;
    }
    private static Object limiter(Class<?> type) throws ReflectiveOperationException {
        for(Method method:type.getMethods()) if(Modifier.isStatic(method.getModifiers()) && method.getReturnType()==type
                && method.getParameterTypes().length==1 && method.getParameterTypes()[0]==long.class) return method.invoke(null,(long)MAX_BYTES);
        try { return type.getConstructor(long.class).newInstance((long)MAX_BYTES); }
        catch(NoSuchMethodException absent) {
            for(Method method:type.getMethods()) if(Modifier.isStatic(method.getModifiers()) && method.getReturnType()==type && method.getParameterTypes().length==0) return method.invoke(null);
            throw absent;
        }
    }
    private static Method bukkitCopy(Class<?> craftItem,Class<?> itemType) throws NoSuchMethodException {
        Method result=null;
        for(Method method:craftItem.getMethods()) {
            Class<?>[] arguments=method.getParameterTypes();
            if(!method.getName().equals("asBukkitCopy") || !Modifier.isStatic(method.getModifiers())
                    || arguments.length!=1 || !arguments[0].isAssignableFrom(itemType)
                    || !ItemStack.class.isAssignableFrom(method.getReturnType())) continue;
            if(result==null || result.getParameterTypes()[0].isAssignableFrom(arguments[0])) result=method;
        }
        if(result==null) throw new NoSuchMethodException("CraftItemStack.asBukkitCopy accepting " + itemType.getName());
        return result;
    }
    private static Class<?> load(ClassLoader loader,String... names) throws ClassNotFoundException {
        for(String name:names) try { return Class.forName(name,false,loader); } catch(ClassNotFoundException absent) { }
        throw new ClassNotFoundException(Arrays.toString(names));
    }
    private static byte[] compress(Map<String,Object> root) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); try(GZIPOutputStream gzip=new GZIPOutputStream(bytes)) { gzip.write(NbtWriter.write(root,MAX_BYTES,MAX_DEPTH)); }
        return bytes.toByteArray();
    }
}


