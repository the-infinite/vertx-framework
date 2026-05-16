package io.github.the_infinite.core.data.types;

import io.github.the_infinite.core.data.BaseEntity;

import org.hibernate.reactive.mutiny.Mutiny;

public interface StatelessChangeEffector <TModel extends BaseEntity> {
    boolean change(final Mutiny.StatelessSession session, TModel entity);
}
