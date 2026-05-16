package io.github.the_infinite.core.models;

import io.github.the_infinite.core.data.BaseEntity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
@SuppressWarnings("unused")
public class Product extends BaseEntity {
  @Column(unique = true)
  private String name;

  @Column(nullable = false)
  private BigDecimal price;

  public Product() {
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(BigDecimal price) {
    this.price = price;
  }
}
