package com.trained.project.springbootai.learning.moth01.day01;

public class HelloJVM {

    static  int counter = 0;

    public  int add(int a, int b){
        return  a + b;
    }

    public static void main(String[] args) throws InterruptedException {
        HelloJVM app = new HelloJVM();
        for (int i = 0; i < 13; i++){
            counter = app.add(counter, i);
            Thread.sleep(4000);
        }
        System.out.println(counter);


      /*
         javap -v HelloJVM.class
        Classfile /D:/mywork/springbootai/src/main/java/com/trained/project/springbootai/learning/moth01/HelloJVM.class
          Last modified 2026年9月23日; size 676 bytes
          SHA-256 checksum 9d053f92a5e37d608c68bbf9372a96936e91b3b7e828121926a1e20062fb3898
          Compiled from "HelloJVM.java"
        public class com.trained.project.springbootai.learning.moth01.HelloJVM
          minor version: 0
          major version: 69
          flags: (0x0021) ACC_PUBLIC, ACC_SUPER
          this_class: #7                          // com/trained/project/springbootai/learning/moth01/HelloJVM
          super_class: #2                         // java/lang/Object
          interfaces: 0, fields: 1, methods: 4, attributes: 1
        Constant pool:
           #1 = Methodref          #2.#3          // java/lang/Object."<init>":()V
           #2 = Class              #4             // java/lang/Object
           #3 = NameAndType        #5:#6          // "<init>":()V
           #4 = Utf8               java/lang/Object
           #5 = Utf8               <init>
           #6 = Utf8               ()V
           #7 = Class              #8             // com/trained/project/springbootai/learning/moth01/HelloJVM
           #8 = Utf8               com/trained/project/springbootai/learning/moth01/HelloJVM
           #9 = Methodref          #7.#3          // com/trained/project/springbootai/learning/moth01/HelloJVM."<init>":()V
          #10 = Fieldref           #7.#11         // com/trained/project/springbootai/learning/moth01/HelloJVM.counter:I
          #11 = NameAndType        #12:#13        // counter:I
          #12 = Utf8               counter
          #13 = Utf8               I
          #14 = Methodref          #7.#15         // com/trained/project/springbootai/learning/moth01/HelloJVM.add:(II)I
          #15 = NameAndType        #16:#17        // add:(II)I
          #16 = Utf8               add
          #17 = Utf8               (II)I
          #18 = Fieldref           #19.#20        // java/lang/System.out:Ljava/io/PrintStream;
          #19 = Class              #21            // java/lang/System
          #20 = NameAndType        #22:#23        // out:Ljava/io/PrintStream;
          #21 = Utf8               java/lang/System
          #22 = Utf8               out
          #23 = Utf8               Ljava/io/PrintStream;
          #24 = Methodref          #25.#26        // java/io/PrintStream.println:(I)V
          #25 = Class              #27            // java/io/PrintStream
          #26 = NameAndType        #28:#29        // println:(I)V
          #27 = Utf8               java/io/PrintStream
          #28 = Utf8               println
          #29 = Utf8               (I)V
          #30 = Utf8               Code
          #31 = Utf8               LineNumberTable
          #32 = Utf8               main
          #33 = Utf8               ([Ljava/lang/String;)V
          #34 = Utf8               StackMapTable
          #35 = Utf8               <clinit>
          #36 = Utf8               SourceFile
          #37 = Utf8               HelloJVM.java
        {
          static int counter;
            descriptor: I
            flags: (0x0008) ACC_STATIC
        
          public com.trained.project.springbootai.learning.moth01.HelloJVM();
            descriptor: ()V
            flags: (0x0001) ACC_PUBLIC
            Code:
              stack=1, locals=1, args_size=1
                 0: aload_0
                 1: invokespecial #1                  // Method java/lang/Object."<init>":()V
                 4: return
              LineNumberTable:
                line 3: 0
        
          public int add(int, int);
            descriptor: (II)I
            flags: (0x0001) ACC_PUBLIC
            Code:
              stack=2, locals=3, args_size=3
                 0: iload_1
                 1: iload_2
                 2: iadd
                 3: ireturn
              LineNumberTable:
                line 8: 0
        
          public static void main(java.lang.String[]);
            descriptor: ([Ljava/lang/String;)V
            flags: (0x0009) ACC_PUBLIC, ACC_STATIC
            Code:
              stack=3, locals=3, args_size=1
                 0: new           #7                  // class com/trained/project/springbootai/learning/moth01/HelloJVM
                 3: dup
                 4: invokespecial #9                  // Method "<init>":()V
                 7: astore_1
                 8: iconst_0
                 9: istore_2
                10: iload_2
                11: iconst_3
                12: if_icmpge     32
                15: aload_1
                16: getstatic     #10                 // Field counter:I
                19: iload_2
                20: invokevirtual #14                 // Method add:(II)I
                23: putstatic     #10                 // Field counter:I
                26: iinc          2, 1
                29: goto          10
                32: getstatic     #18                 // Field java/lang/System.out:Ljava/io/PrintStream;
                35: getstatic     #10                 // Field counter:I
                38: invokevirtual #24                 // Method java/io/PrintStream.println:(I)V
                41: return
              LineNumberTable:
                line 12: 0
                line 13: 8
                line 14: 15
                line 13: 26
                line 16: 32
                line 17: 41
              StackMapTable: number_of_entries = 2
                frame_type = 253 *//* append *//*
                  offset_delta = 10
                  locals = [ class com/trained/project/springbootai/learning/moth01/HelloJVM, int ]
                frame_type = 250 *//* chop *//*
                  offset_delta = 21
        
          static {};
            descriptor: ()V
            flags: (0x0008) ACC_STATIC
            Code:
              stack=1, locals=0, args_size=0
                 0: iconst_0
                 1: putstatic     #10                 // Field counter:I
                 4: return
              LineNumberTable:
                line 5: 0
        }
        SourceFile: "HelloJVM.java"

           */

    }

}
