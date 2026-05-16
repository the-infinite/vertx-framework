package io.github.the_infinite.core.utils;

public final class AtomHolder<T> {
    private T atom;

    public AtomHolder() {
    }

    public void set(T atom) {
        this.atom = atom;
    }

    public T get() {
        return atom;
    }
}
