package com.stardust.autojs.rhino;

import android.os.Build;

import com.android.dx.command.dexer.Main;
import com.stardust.pio.PFiles;
import com.stardust.util.MD5;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;

import org.apache.log4j.Logger;
import org.mozilla.javascript.GeneratedClassLoader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import androidx.annotation.RequiresApi;
import dalvik.system.DexClassLoader;

/**
 * Created by Stardust on 2017/4/5.
 */

public class AndroidClassLoader extends ClassLoader implements GeneratedClassLoader {

    private static final Lock lock = new ReentrantLock();
    private static final Set<Integer> dexRunningThreads = new HashSet<>();
    private boolean boom = false;
    private final ClassLoader parent;
    private final Map<String, DexClassLoader> mDexClassLoaders = new LinkedHashMap<>();
    private final File mCacheDir;
    private final File mLibsDir;
    private final Logger logger = Logger.getLogger(AndroidClassLoader.class);

    private final WeakHashMap<DeleteOnFinalizeFile, String> weakDexFileMap = new WeakHashMap<>();

    /**
     * Create a new instance with the given parent classloader and cache dierctory
     *
     * @param parent the parent
     * @param dir    the cache directory
     */
    public AndroidClassLoader(ClassLoader parent, File dir) {
        this.parent = parent;
        mCacheDir = dir;
        mLibsDir = new File(dir, "libs");
        if (dir.exists()) {
            if (!(parent instanceof AndroidClassLoader)) {
                PFiles.deleteFilesOfDir(dir);
            }
        } else {
            dir.mkdirs();
        }
        mLibsDir.mkdir();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<?> defineClass(String name, byte[] data) {
        logger.debug("defineClass: name = " + name + " data.length = " + data.length);
        File classFile = null;
        try {
            classFile = generateTempFile(name, false);
            final ZipFile zipFile = new ZipFile(classFile);
            final ZipParameters parameters = new ZipParameters();
            parameters.setFileNameInZip(name.replace('.', '/') + ".class");
            parameters.setSourceExternalStream(true);
            ByteArrayInputStream is = new ByteArrayInputStream(data);
            zipFile.addStream(is, parameters);
            is.close();
            return dexJar(classFile, null).loadClass(name);
        } catch (IOException | ZipException | ClassNotFoundException e) {
            throw new FatalLoadingException(e);
        } finally {
            if (classFile != null) {
                classFile.delete();
            }
        }
    }

    private File generateTempFile(String name, boolean create) throws IOException {
        File file = new File(mCacheDir, name.hashCode() + "_" + UUID.randomUUID() + ".jar");
        if (create) {
            if (!file.exists()) {
                file.createNewFile();
            }
        } else {
            file.delete();
        }
        return file;
    }

    public void loadJar(File jar) throws IOException {
        logger.debug("loadJar: jar = " + jar);
        if (!jar.exists() || !jar.canRead()) {
            throw new FileNotFoundException("File does not exist or readable: " + jar.getPath());
        }
        File dexFile = new File(mCacheDir, generateDexFileName(jar));
        if (dexFile.exists()) {
            loadDex(dexFile);
            return;
        } else {
            dexFile.createNewFile();
        }
        try {
            final File classFile = generateTempFile(jar.getPath(), false);
            final ZipFile zipFile = new ZipFile(classFile);
            final ZipFile jarFile = new ZipFile(jar);
            //noinspection unchecked
            for (FileHeader header : (List<FileHeader>) jarFile.getFileHeaders()) {
                if (!header.isDirectory()) {
                    final ZipParameters parameters = new ZipParameters();
                    parameters.setFileNameInZip(header.getFileName());
                    parameters.setSourceExternalStream(true);
                    zipFile.addStream(jarFile.getInputStream(header), parameters);
                }
            }
            dexJar(classFile, dexFile);
            classFile.delete();
        } catch (ZipException e) {
            throw new IOException(e);
        }
    }

    private String generateDexFileName(File jar) {
        String message = jar.getPath() + "_" + jar.lastModified();
        return MD5.md5(message);
    }

    public DexClassLoader loadDex(File file) throws FileNotFoundException {
        logger.debug("loadDex: file = " + file);
        if (!file.exists()) {
            throw new FileNotFoundException(file.getPath());
        }
        logger.debug("dex file size: " + file.length());
        DexClassLoader loader = new DexClassLoader(file.getPath(), mCacheDir.getPath(), mLibsDir.getPath(), parent);
        // 根据dex文件名 移除已有的，使得最新载入的在LinkedHashMap末尾
        synchronized (mDexClassLoaders) {
            mDexClassLoaders.remove(file.getName());
            mDexClassLoaders.put(file.getName(), loader);
        }
        return loader;
    }

    /**
     * 移除已加载的dex文件
     */
    public synchronized void unloadAllDex() {
        synchronized (mDexClassLoaders) {
            PFiles.deleteFilesOfDir(mCacheDir);
            this.mDexClassLoaders.clear();
            if (!mCacheDir.exists()) {
                mCacheDir.mkdirs();
            }
            if (!mLibsDir.exists()) {
                mLibsDir.mkdir();
            }
        }
    }

    public boolean isBoom() {
        return boom;
    }

    private void persistThread() {
        int hashCode = Thread.currentThread().hashCode();
        logger.debug("当前线程加入dex运行中 hashCode: " + hashCode + " is boom: " + boom);
        dexRunningThreads.add(hashCode);
    }

    private void removeThread() {
        int hashCode = Thread.currentThread().hashCode();
        logger.debug("当前线程从dex运行中移除 hashCode: " + hashCode);
        dexRunningThreads.remove(hashCode);
    }

    public void waitIfOnDex(Thread thread) {
        if (thread == null) {
            return;
        }
        int hashCode = thread.hashCode();
        logger.debug("校验指定线程是否在dex中 hashCode: " + hashCode + " current thread: " + Thread.currentThread().hashCode());
        int limit = 50;
        while (dexRunningThreads.contains(hashCode) && limit-- > 0) {
            try {
                // 等待执行完毕
                Thread.sleep(100);
                logger.debug("等待当前线程dex执行完毕 hashCode: " + hashCode + " idx: " + (50 - limit));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private DexClassLoader dexJar(File classFile, File dexFile) throws IOException {
        String id = UUID.randomUUID().toString();
        boolean locked = false;
        if (dexFile != null) {
            logger.debug(id + ": 等待获取锁并生成dex, class file: " + classFile.getName());
            lock.lock();
            locked = true;
        }
        try {
            persistThread();
            if (locked)
                logger.debug(id + ": 获取锁开始生成dex");
            final Main.Arguments arguments = new Main.Arguments();
            arguments.fileNames = new String[]{classFile.getPath()};
            boolean isTmpDex = dexFile == null;
            if (isTmpDex) {
                dexFile = generateTempFile("dex-" + classFile.getPath(), true);
            }
            arguments.outName = dexFile.getPath();
            arguments.jarOutput = true;
            arguments.verbose = true;
            int resultCode = Main.run(arguments);
            logger.debug(id + ": 生成dex完毕 resultCode: " + resultCode + ", dex file: " + dexFile.getName());
            logger.debug("dex file size after dexJar: " + dexFile.length());
            if (dexFile.length() == 0) {
                boom = true;
                // 出现了异常
                logger.error("创建dex文件失败，class文件路径：" + classFile.getPath() + " 大小：" + classFile.length());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    dumpClassfileIntoLog(classFile);
                }
            }
            DexClassLoader loader = loadDex(dexFile);
            if (isTmpDex) {
                logger.debug("delete tmpFile on finalize:" + dexFile.getName());
                // 当弱引用失去引用时 删除File对象
                weakDexFileMap.put(new DeleteOnFinalizeFile(dexFile), dexFile.getName());
                logger.debug("current weakMap size:" + weakDexFileMap.size());
            }
            logger.debug(id + ": dexJar完成");
            return loader;
        } finally {
            removeThread();
            if (locked) {
                logger.debug(id + ": 释放锁");
                lock.unlock();
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void dumpClassfileIntoLog(File classFile) {
        try (
                FileInputStream fis = new FileInputStream(classFile);
        ) {
            byte[] buff = new byte[(int) classFile.length()];
            fis.read(buff);
            String base64 = Base64.getEncoder().encodeToString(buff);
            logger.debug("class file content base64:" + base64);
        } catch (Exception e) {
            logger.error("备份class文件异常", e);
        }
    }

    /**
     * Does nothing
     *
     * @param aClass ignored
     */
    @Override
    public void linkClass(Class<?> aClass) {
        //doesn't make sense on android
    }

    /**
     * Try to load a class. This will search all defined classes, all loaded jars and the parent class loader.
     *
     * @param name    the name of the class to load
     * @param resolve ignored
     * @return the class
     * @throws ClassNotFoundException if the class could not be found in any of the locations
     */
    @Override
    public Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        Class<?> loadedClass = findLoadedClass(name);
        if (loadedClass == null) {
            if (parent != null) {
                try {
                    loadedClass = parent.loadClass(name);
                } catch (Exception e) {
                    // do nothing
                }
            }
            if (loadedClass == null) {
                synchronized (mDexClassLoaders) {
                    ListIterator<DexClassLoader> reverseIterator = new ArrayList<>(mDexClassLoaders.values()).listIterator(mDexClassLoaders.size());
                    while (reverseIterator.hasPrevious()) {
                        DexClassLoader classLoader = reverseIterator.previous();
                        try {
                            loadedClass = classLoader.loadClass(name);
                        } catch (Exception e) {
                            // do nothing
                        }
                        if (loadedClass != null) {
                            break;
                        }
                    }
                }
            }
            if (loadedClass == null) {
                loadedClass = findClass(name);
            }
        }
        return loadedClass;
    }

    /**
     * Might be thrown in any Rhino method that loads bytecode if the loading failed
     */
    public static class FatalLoadingException extends RuntimeException {
        FatalLoadingException(Throwable t) {
            super("Failed to define class", t);
        }
    }

    public String getLibsDir() {
        return mLibsDir.getPath();
    }
}
