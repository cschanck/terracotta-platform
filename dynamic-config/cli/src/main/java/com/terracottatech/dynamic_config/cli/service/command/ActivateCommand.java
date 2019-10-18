/*
 * Copyright (c) 2011-2019 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA, and/or its subsidiaries and/or its affiliates and/or their licensors.
 * Use, reproduction, transfer, publication or disclosure is prohibited except as specifically provided for in your License Agreement with Software AG.
 */
package com.terracottatech.dynamic_config.cli.service.command;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.PathConverter;
import com.terracottatech.diagnostic.client.connection.DiagnosticServices;
import com.terracottatech.dynamic_config.cli.common.InetSocketAddressConverter;
import com.terracottatech.dynamic_config.cli.common.Usage;
import com.terracottatech.dynamic_config.model.Cluster;
import com.terracottatech.dynamic_config.model.NodeContext;
import com.terracottatech.dynamic_config.model.config.ClusterCreator;
import com.terracottatech.dynamic_config.model.validation.ClusterValidator;
import com.terracottatech.dynamic_config.nomad.ClusterActivationNomadChange;
import com.terracottatech.dynamic_config.util.IParameterSubstitutor;
import com.terracottatech.nomad.client.results.NomadFailureRecorder;
import com.terracottatech.utilities.Tuple2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static com.terracottatech.dynamic_config.util.IParameterSubstitutor.identity;
import static com.terracottatech.utilities.Assertions.assertNonNull;

@Parameters(commandNames = "activate", commandDescription = "Activate the cluster")
@Usage("activate <-s HOST[:PORT] | -f CONFIG-PROPERTIES-FILE> -n CLUSTER-NAME -l LICENSE-FILE")
public class ActivateCommand extends RemoteCommand {

  @Parameter(names = {"-s"}, description = "Node to connect to", converter = InetSocketAddressConverter.class)
  private InetSocketAddress node;

  @Parameter(names = {"-f"}, description = "Config properties file", converter = PathConverter.class)
  private Path configPropertiesFile;

  @Parameter(names = {"-n"}, description = "Cluster name")
  private String clusterName;

  @Parameter(required = true, names = {"-l"}, description = "Path to license file", converter = PathConverter.class)
  private Path licenseFile;

  private final IParameterSubstitutor substitutor = identity();

  private Cluster cluster;
  private Collection<InetSocketAddress> runtimePeers;

  @Override
  public void validate() {
    if (node == null && configPropertiesFile == null) {
      throw new IllegalArgumentException("One of node or config properties file must be specified");
    }

    if (node != null && configPropertiesFile != null) {
      throw new IllegalArgumentException("Either node or config properties file should be specified, not both");
    }

    if (node != null && clusterName == null) {
      throw new IllegalArgumentException("Cluster name should be provided when node is specified");
    }

    if (node != null) {
      ensureAddressWithinCluster(node);
    }

    assertNonNull(licenseFile, "licenseFile must not be null");
    assertNonNull(multiDiagnosticServiceProvider, "multiDiagnosticServiceProvider must not be null");
    assertNonNull(nomadManager, "nomadManager must not be null");
    assertNonNull(restartService, "restartService must not be null");

    cluster = loadCluster();
    runtimePeers = node == null ? cluster.getNodeAddresses() : findRuntimePeers(node);

    // check if we want to override the cluster name
    if (clusterName != null) {
      logger.info("Setting cluster name to: {}", clusterName);
      cluster.setName(clusterName);
    } else {
      clusterName = cluster.getName();
    }

    if (cluster.getName() == null) {
      throw new IllegalArgumentException("Cluster name is missing");
    }

    // validate the topology
    new ClusterValidator(cluster, substitutor).validate();

    // verify the activated state of the nodes
    boolean isClusterActive = areAllNodesActivated(runtimePeers);
    if (isClusterActive) {
      throw new IllegalStateException("Cluster is already activated");
    }
  }

  @Override
  public final void run() {
    try (DiagnosticServices diagnosticServices = multiDiagnosticServiceProvider.fetchDiagnosticServices(runtimePeers)) {
      prepareActivation(diagnosticServices);
      logger.info("License installation successful");

      runNomadChange();
      logger.debug("Configuration repositories have been created for all nodes");

      logger.info("Restarting nodes: {}", toString(runtimePeers));
      restartNodes(runtimePeers);
      logger.info("All nodes came back up: {}", toString(runtimePeers));
    }

    logger.info("Command successful!\n");
  }

  /*<-- Test methods --> */
  Cluster getCluster() {
    return cluster;
  }

  ActivateCommand setNode(InetSocketAddress node) {
    this.node = node;
    return this;
  }

  ActivateCommand setConfigPropertiesFile(Path configPropertiesFile) {
    this.configPropertiesFile = configPropertiesFile;
    return this;
  }

  ActivateCommand setClusterName(String clusterName) {
    this.clusterName = clusterName;
    return this;
  }

  ActivateCommand setLicenseFile(Path licenseFile) {
    this.licenseFile = licenseFile;
    return this;
  }

  String getClusterName() {
    return clusterName;
  }

  private Cluster loadCluster() {
    Cluster cluster;
    if (node != null) {
      cluster = getUpcomingCluster(node);
      logger.debug("Cluster topology validation successful");
    } else {
      ClusterCreator clusterCreator = new ClusterCreator(substitutor);
      cluster = clusterCreator.create(configPropertiesFile, clusterName);
      logger.debug("Config property file parsed and cluster topology validation successful");
    }
    return cluster;
  }

  private void prepareActivation(DiagnosticServices diagnosticServices) {
    dynamicConfigServices(diagnosticServices)
        .map(Tuple2::getT2)
        .forEach(service -> service.prepareActivation(cluster, read(licenseFile)));
  }

  private void runNomadChange() {
    NomadFailureRecorder<NodeContext> failures = new NomadFailureRecorder<>();
    nomadManager.runChange(runtimePeers, new ClusterActivationNomadChange(cluster), failures);
    failures.reThrow();
  }

  private static String read(Path path) {
    try {
      return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
