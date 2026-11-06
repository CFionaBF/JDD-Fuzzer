package com.pholser.junit.quickcheck.generator.java.lang;

import com.pholser.junit.quickcheck.random.SourceOfRandomness;

public class StringGenerator extends AbstractStringGenerator {
    @Override
    protected int nextCodePoint(SourceOfRandomness var1) {
        return 0;
    }

    @Override
    protected boolean codePointInRange(int var1) {
        return false;
    }
}
