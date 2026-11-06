package cn.edu.fudan.generator.seed;

import java.io.Serializable;

/**
 * Placeholder put on both sides of a type-2 hash collision to find out, by deserializing a copy of
 * the seed, which collision object calls equals() on the other one (the receiver side).
 * All probes hash alike so the two collision objects keep colliding.
 */
public class CollisionSideProbe implements Serializable {

    private static final long serialVersionUID = 1L;

    // side of the probe whose equals() ran last; reset before each probing
    static volatile String lastReceiver;

    private final String side;

    CollisionSideProbe(String side) {
        this.side = side;
    }

    @Override
    public boolean equals(Object o) {
        lastReceiver = side;
        return false;
    }

    @Override
    public int hashCode() {
        return 0;
    }
}
