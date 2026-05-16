#!/bin/zsh
mvn clean package
mvn install:install-file -Dfile=./target/framework-1.0.0-Final-jar-with-dependencies.jar -DgroupId=io.github.the_infinite \
  -DartifactId=framework -Dversion=1.0.0-Final -Dpackaging=jar;
