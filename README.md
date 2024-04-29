![LDBC Logo](ldbc-logo.png)
# LDBC SNB Interactive v2 driver

[![Build Status](https://circleci.com/gh/ldbc/ldbc_snb_interactive_v2_driver.svg?style=svg)](https://circleci.com/gh/ldbc/ldbc_snb_interactive_v2_driver)

This driver runs the Social Network Benchmark's Interactive v2 workload, including cross-validation and benchmark execution.
The implementations of the workload (with DBMSs such as Neo4j and PostgreSQL) are available in <https://github.com/ldbc/ldbc_snb_interactive_v2_impls>.

The design and implementation of the SNB Interactive v2 workload are described in the [TPCTC 2023 paper, "The LDBC Social Network Benchmark Interactive Workload v2: A Transactional Graph Query Benchmark with Deep Delete Operations"](https://ldbcouncil.org/docs/papers/ldbc-snb-interactive-v2-tpctc2023-preprint.pdf) by Püroja et al.

## User Guide

Clone and build with Maven:

```bash
git clone https://github.com/ldbc/ldbc_snb_interactive_v2_driver
cd ldbc_snb_interactive_v2_driver
mvn clean package -DskipTests
```

To quickly test the driver try the "simpleworkload" that is shipped with it by doing the following:

```bash
java \
  -cp target/driver-standalone.jar org.ldbcouncil.snb.driver.Client \
  -db org.ldbcouncil.snb.driver.workloads.simple.db.SimpleDb \
  -P target/classes/configuration/simple/simpleworkload.properties \
  -P target/classes/configuration/ldbc_driver_default.properties
```

For more information, please refer to the [Documentation](https://github.com/ldbc/ldbc_driver/wiki).

## Deploying Maven Artifacts

We use a manual process for deploying Maven artifacts.

1. Clone the [`snb-mvn` repository](https://github.com/ldbc/snb-mvn) next to the driver repository's directory.

2. In the driver repository, run:

    ```bash
    scripts/package-mvn-artifacts.sh
    ```

3. Go to the `snb-mvn` directory, check whether the JAR files are correct.

4. Commit and push.

## Audited runs

Implementations of the Interactive workload can be [audited](https://ldbcouncil.org/benchmarks/snb-interactive/) by a certified LDBC auditor.
The [Auditing Policies chapter of the specification](https://ldbcouncil.org/ldbc_snb_docs/ldbc-snb-specification.pdf) describes the auditing process and the required artifacts.

If you plan to get your system audited, please reach out to the [LDBC Board of Directors](https://ldbcouncil.org/leadership/).
