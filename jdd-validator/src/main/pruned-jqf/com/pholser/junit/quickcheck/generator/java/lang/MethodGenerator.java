package com.pholser.junit.quickcheck.generator.java.lang;

import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import java.lang.reflect.Method;

public class MethodGenerator extends Generator<Method> {
    protected MethodGenerator() {
        super(Method.class);
    }

    @Override
    public Method generate(SourceOfRandomness sourceOfRandomness, GenerationStatus generationStatus) {
        return sourceOfRandomness.choose(ValidatorGlobalDataContainer.singletonFuzzDictContainer.methodDic);
    }
}
