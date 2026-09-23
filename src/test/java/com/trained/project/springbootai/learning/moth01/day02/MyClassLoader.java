package com.trained.project.springbootai.learning.moth01.day02;

import com.trained.project.springbootai.learning.moth01.day01.HelloJVM;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MyClassLoader extends  ClassLoader{
    private  final Path classDir;

    public MyClassLoader(Path classDir) {
        this.classDir = classDir;
    }

    /**
     * 这个是true 回去找父类加载器询问是否加载了
     * @param name
     *          The <a href="#binary-name">binary name</a> of the class
     *
     * @return
     * @throws ClassNotFoundException
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            byte[] bytes = Files.readAllBytes(classDir.resolve(name+".class"));
            return  defineClass(name, bytes, 0, bytes.length);
        }catch (Exception e){
           throw  new ClassNotFoundException(name, e);
        }

    }
    /**
     * 这个是false 自己加载
     * @param name
     *          The <a href="#binary-name">binary name</a> of the class
     *
     * @return
     * @throws ClassNotFoundException
     */
    protected Class<?> findClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
           Class<?> c =  findClass(name);
            if (c == null) {
                c = findClass(name);   // 不问父加载器，直接自己加载
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }catch (Exception e){
            throw  new ClassNotFoundException(name, e);
        }

    }

    public static void main(String[] args) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Path currentDir = Path.of("").toAbsolutePath();
        Path dir = currentDir.resolve("encrypted");
        Path targetDir = dir.resolve("com/trained/project/springbootai/learning/moth01/day02");
        Files.createDirectories(targetDir);

        System.out.println("dir: " + dir);
        Files.copy(Path.of("D:/mywork/springbootai/src/test/java/com/trained/project/springbootai/learning/moth01/day02/HelloJVM2.class"), dir.resolve("HelloJVM2.class"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        MyClassLoader loader = new MyClassLoader(dir);
        ClassLoader p1 = loader.getParent();
        System.out.println("MyClassLoader 的 parent: " + p1);

        ClassLoader p2 = p1.getParent();
        System.out.println("AppClassLoader 的 parent: " + p2);
        /*
        MyClassLoader 的 parent: jdk.internal.loader.ClassLoaders$AppClassLoader@...
        AppClassLoader 的 parent: jdk.internal.loader.ClassLoaders$PlatformClassLoader@...
        PlatformClassLoader 的 parent: null
        null 就代表 Bootstrap，因为 Bootstrap 是 C++ 实现的，Java 里没有对应对象。
        */
        ClassLoader p3 = p2.getParent();
        System.out.println("PlatformClassLoader 的 parent: " + p3);  // null
        Class<?> c = loader.loadClass("com.trained.project.springbootai.learning.moth01.day02.HelloJVM2");
        System.out.println("加载器: " + c.getClassLoader());
        System.out.println("与系统类相同吗: " + (c == HelloJVM2.class));   // true！委派的结果
        c.getMethod("main", String[].class).invoke(null, (Object) new String[0] );

    }
}
