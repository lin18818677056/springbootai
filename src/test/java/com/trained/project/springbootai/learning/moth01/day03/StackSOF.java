package com.trained.project.springbootai.learning.moth01.day03;

public class StackSOF {
    private  int depth = 0;
    private  void  recurs(){
        depth++;
        recurs();
    }

    public static void main(String[] args) {
        StackSOF stackSOF = new StackSOF();
        try {
            stackSOF.recurs();
        } catch (StackOverflowError  e) {
            System.out.println("栈最大深度 = " + stackSOF.depth);
        }finally {

        }



    }

}
