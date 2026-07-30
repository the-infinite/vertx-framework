package io.github.the_infinite.framework.data.types;

import io.github.the_infinite.framework.data.BaseEntity;

import org.hibernate.StatelessSession;

public interface StatelessChangeEffector <TModel extends BaseEntity> {
    boolean change(final StatelessSession session, TModel entity);
}
