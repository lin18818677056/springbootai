package com.trained.project.springbootai.learning.moth01.day02;

public class InitOrder {
    static  int a = 10;
    static  final  int B =10;
    static {
        System.out.println("静态块执行， 此时 a=" + a);
    }

    public static void main(String[] args) {
        System.out.println("main: a =" + a + ", B=" + B);
    }
}
