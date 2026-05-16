#!/bin/zsh
mvn install:install-file -Dfile=./target/shared-framework.jar -DgroupId=io.github.the_infinite \
  -DartifactId=core -Dversion=1.0.0-Final -Dpackaging=jar;
