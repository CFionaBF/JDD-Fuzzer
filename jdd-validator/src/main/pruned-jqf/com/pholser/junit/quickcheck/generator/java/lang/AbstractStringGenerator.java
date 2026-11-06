package com.pholser.junit.quickcheck.generator.java.lang;

import cn.edu.fudan.container.ValidatorGlobalDataContainer;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.internal.Lists;
import com.pholser.junit.quickcheck.internal.Sequences;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public abstract class AbstractStringGenerator extends Generator<String> {
    protected AbstractStringGenerator() {
        super(String.class);
    }

    @Override
    public String generate(SourceOfRandomness random, GenerationStatus status) {
        return random.choose(ValidatorGlobalDataContainer.singletonFuzzDictContainer.stringDic.toArray(new String[0]));
    }

    public boolean canShrink(Object larger) {
        return super.canShrink(larger) && this.codePointsInRange((String)larger);
    }

    public List<String> doShrink(SourceOfRandomness random, String larger) {
        List<Integer> codePoints = (List)larger.codePoints().boxed().collect(Collectors.toList());
        List<String> shrinks = new ArrayList(this.removals(codePoints));
        List<List<Integer>> oneItemShrinks = Lists.shrinksOfOneItem(random, codePoints, new CodePointShrink(this::codePointInRange));
        shrinks.addAll((Collection)oneItemShrinks.stream().map(this::convert).filter(this::codePointsInRange).collect(Collectors.toList()));
        return shrinks;
    }

    public BigDecimal magnitude(Object value) {
        return BigDecimal.valueOf((long)((String)this.narrow(value)).length());
    }

    protected abstract int nextCodePoint(SourceOfRandomness var1);

    protected abstract boolean codePointInRange(int var1);

    private boolean codePointsInRange(String s) {
        return s.codePoints().allMatch(this::codePointInRange);
    }

    private List<String> removals(List<Integer> codePoints) {
        return (List) StreamSupport.stream(Sequences.halving(codePoints.size()).spliterator(), false).map((i) -> {
            return Lists.removeFrom(codePoints, i);
        }).flatMap(Collection::stream).map(this::convert).collect(Collectors.toList());
    }

    private String convert(List<Integer> codePoints) {
        StringBuilder s = new StringBuilder();
        codePoints.forEach(s::appendCodePoint);
        return s.toString();
    }
}
