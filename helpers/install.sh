#!/bin/zsh

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
project_dir="$(cd -- "${script_dir}/.." && pwd)"
pom_file="${project_dir}/pom.xml"
maven_cmd="mvn"

version="$("${maven_cmd}" -q -f "${pom_file}" -Dexpression=project.version -DforceStdout help:evaluate | tail -n 1)"

cd "${project_dir}"
"${maven_cmd}" clean package -DskipTests
"${maven_cmd}" install:install-file -Dfile="${project_dir}/target/shared-framework.jar" -DgroupId=io.github.the_infinite \
  -DartifactId=framework -Dversion="${version}" -Dpackaging=jar -DskipTests
