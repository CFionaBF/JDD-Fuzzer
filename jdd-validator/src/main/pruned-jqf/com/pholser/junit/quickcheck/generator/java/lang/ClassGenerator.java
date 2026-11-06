package com.pholser.junit.quickcheck.generator.java.lang;

import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

public class ClassGenerator extends Generator<Class> {
    protected ClassGenerator() {
        super(Class.class);
    }

    @Override
    public Class generate(SourceOfRandomness sourceOfRandomness, GenerationStatus generationStatus) {
        return sourceOfRandomness.choose(ValidatorGlobalDataContainer.singletonFuzzDictContainer.classDic);
    }
}
