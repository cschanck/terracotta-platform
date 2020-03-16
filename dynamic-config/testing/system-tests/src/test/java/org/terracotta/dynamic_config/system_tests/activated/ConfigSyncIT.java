/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.dynamic_config.system_tests.activated;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.dynamic_config.test_support.ClusterDefinition;
import org.terracotta.dynamic_config.test_support.DynamicConfigIT;
import org.terracotta.dynamic_config.test_support.util.NodeOutputRule;
import org.terracotta.json.Json;
import org.terracotta.persistence.sanskrit.JsonUtils;
import org.terracotta.persistence.sanskrit.MutableSanskritObject;
import org.terracotta.persistence.sanskrit.SanskritException;
import org.terracotta.persistence.sanskrit.SanskritImpl;
import org.terracotta.persistence.sanskrit.SanskritObject;
import org.terracotta.persistence.sanskrit.SanskritObjectImpl;
import org.terracotta.persistence.sanskrit.file.FileBasedFilesystemDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.terracotta.dynamic_config.server.configuration.nomad.persistence.NomadSanskritKeys.CHANGE_OPERATION;
import static org.terracotta.dynamic_config.server.configuration.nomad.persistence.NomadSanskritKeys.CHANGE_STATE;
import static org.terracotta.dynamic_config.server.configuration.nomad.persistence.NomadSanskritKeys.CHANGE_VERSION;
import static org.terracotta.dynamic_config.server.configuration.nomad.persistence.NomadSanskritKeys.LATEST_CHANGE_UUID;
import static org.terracotta.dynamic_config.server.configuration.nomad.persistence.NomadSanskritKeys.MODE;
import static org.terracotta.dynamic_config.server.configuration.nomad.persistence.NomadSanskritKeys.MUTATIVE_MESSAGE_COUNT;
import static org.terracotta.dynamic_config.server.configuration.nomad.persistence.NomadSanskritKeys.PREV_CHANGE_UUID;
import static org.terracotta.dynamic_config.test_support.util.AngelaMatchers.containsLog;
import static org.terracotta.dynamic_config.test_support.util.AngelaMatchers.hasExitStatus;
import static org.terracotta.dynamic_config.test_support.util.AngelaMatchers.successful;

@ClusterDefinition(nodesPerStripe = 2, autoActivate = true)
public class ConfigSyncIT extends DynamicConfigIT {

  @Rule public final NodeOutputRule out = new NodeOutputRule();

  //TODO [DYNAMIC-CONFIG]: TDB-4863 - fix Angela to properly redirect process error streams
  @Rule public final SystemErrRule err = new SystemErrRule().enableLog();

  private int activeNodeId;
  private int passiveNodeId;

  @Before
  @Override
  public void before() {
    super.before();
    if (tsa.getActive() == getNode(1, 1)) {
      activeNodeId = 1;
      passiveNodeId = 2;
    } else {
      activeNodeId = 2;
      passiveNodeId = 1;
    }
  }

  @Test
  public void testPassiveSyncingAppendChangesFromActive() throws Exception {
    stopNode(1, passiveNodeId);
    assertThat(tsa.getStopped().size(), is(1));

    assertThat(configToolInvocation("set", "-s", "localhost:" + getNodePort(1, activeNodeId), "-c", "offheap-resources.main=1GB"), is(successful()));

    //TODO TDB-4842: The stop and corresponding start is needed to prevent IOException on Windows
    // Passive is already stopped, so only shutdown and restart the active
    stopNode(1, activeNodeId);
    assertThat(tsa.getStopped().size(), is(2));
    assertContentsBeforeOrAfterSync(5, 3);
    tsa.start(getNode(1, activeNodeId));
    assertThat(tsa.getActives().size(), is(1));

    out.clearLog(1, passiveNodeId);
    tsa.start(getNode(1, passiveNodeId));
    waitUntil(out.getLog(1, passiveNodeId), containsLog("Moved to State[ PASSIVE-STANDBY ]"));

    verifyTopologies();

    //TODO TDB-4842: The stop is needed to prevent IOException on Windows
    tsa.stopAll();
    assertContentsBeforeOrAfterSync(5, 5);
  }

  @Test
  public void testPassiveZapsWhenActiveHasSomeUnCommittedChanges() throws Exception {
    stopNode(1, passiveNodeId);
    assertThat(tsa.getStopped().size(), is(1));

    // trigger commit failure on active
    // the passive should zap when restarting
    assertThat(
        configToolInvocation("set", "-s", "localhost:" + getNodePort(1, activeNodeId), "-c", "stripe.1.node." + activeNodeId + ".node-logger-overrides.org.terracotta.dynamic-config.simulate=INFO"),
        not(hasExitStatus(0)));

    //TODO TDB-4842: The stop and corresponding start is needed to prevent IOException on Windows
    // Passive is already stopped, so only shutdown and restart the active
    stopNode(1, activeNodeId);
    assertThat(tsa.getStopped().size(), is(2));
    assertContentsBeforeOrAfterSync(4, 3);
    tsa.start(getNode(1, activeNodeId));
    assertThat(tsa.getActives().size(), is(1));

    err.clearLog();
    try {
      tsa.start(getNode(1, passiveNodeId));
      fail();
    } catch (Exception e) {
      waitUntil(err::getLog, containsString("Active has some PREPARED configuration changes that are not yet committed."));
    }

    //TODO TDB-4842: The stop is needed to prevent IOException on Windows
    tsa.stopAll();
    assertContentsBeforeOrAfterSync(4, 3);
  }

  @Test
  public void testPassiveZapsAppendLogHistoryMismatch() throws Exception {
    // trigger commit failure on active
    // but passive is fine
    // when passive restarts, its history is greater and not equal to the active, so it zaps
    assertThat(
        configToolInvocation("set", "-s", "localhost:" + getNodePort(1, activeNodeId), "-c", "stripe.1.node." + activeNodeId + ".node-logger-overrides.org.terracotta.dynamic-config.simulate=INFO"),
        not(hasExitStatus(0)));

    //TODO TDB-4842: The stop and corresponding start is needed to prevent IOException on Windows
    stopNode(1, passiveNodeId);
    stopNode(1, activeNodeId);
    assertThat(tsa.getStopped().size(), is(2));
    assertContentsBeforeOrAfterSync(4, 5);
    // Start only the former active for now (the passive startup would be done later, and should fail)
    tsa.start(getNode(1, activeNodeId));
    assertThat(tsa.getActives().size(), is(1));

    err.clearLog();
    try {
      tsa.start(getNode(1, passiveNodeId));
      fail();
    } catch (Exception e) {
      waitUntil(err::getLog, containsString("Passive cannot sync because the configuration change history does not match"));
    }

    //TODO TDB-4842: The stop is needed to prevent IOException on Windows
    tsa.stopAll();
    assertContentsBeforeOrAfterSync(4, 5);
  }

  @Test
  public void testPassiveCanSyncAndRepairIfLatestChangeNotCommitted() throws Exception {
    // run a non committed configuration change on the passive
    // the active is OK
    // the passive should restart fine
    assertThat(
        configToolInvocation("set", "-s", "localhost:" + getNodePort(1, passiveNodeId), "-c", "stripe.1.node." + passiveNodeId + ".node-logger-overrides.org.terracotta.dynamic-config.simulate=DEBUG"),
        not(hasExitStatus(0)));

    //TODO TDB-4842: The stop is needed to prevent IOException on Windows
    stopNode(1, passiveNodeId);
    stopNode(1, activeNodeId);
    assertThat(tsa.getStopped().size(), is(2));
    assertContentsBeforeOrAfterSync(5, 4);
    tsa.start(getNode(1, activeNodeId));

    out.clearLog(1, passiveNodeId);
    tsa.start(getNode(1, passiveNodeId));
    waitUntil(out.getLog(1, passiveNodeId), containsLog("Moved to State[ PASSIVE-STANDBY ]"));

    verifyTopologies();

    //TODO TDB-4842: The stop is needed to prevent IOException on Windows
    tsa.stopAll();
    assertContentsBeforeOrAfterSync(5, 5);
  }

  private void assertContentsBeforeOrAfterSync(int activeChangesSize, int passiveChangesSize) throws SanskritException, IOException {
    TerracottaServer active = getNode(1, activeNodeId);
    TerracottaServer passive = getNode(1, passiveNodeId);

    Path activePath = getBaseDir().resolve("activeRepo");
    Path passivePath = getBaseDir().resolve("passiveRepo");
    Files.createDirectories(activePath);
    Files.createDirectories(passivePath);

    tsa.browse(active, Paths.get(active.getConfigRepo()).resolve("sanskrit").toString()).downloadTo(activePath.toFile());
    tsa.browse(passive, Paths.get(passive.getConfigRepo()).resolve("sanskrit").toString()).downloadTo(passivePath.toFile());

    List<SanskritObject> activeChanges = getChanges(activePath);
    List<SanskritObject> passiveChanges = getChanges(passivePath);

    assertThat(activeChanges.size(), is(activeChangesSize));
    assertThat(passiveChanges.size(), is(passiveChangesSize));

    for (int i = 0, till = Math.min(activeChangesSize, passiveChangesSize); i < till; ++i) {
      SanskritObject activeSanskritObject = activeChanges.get(i);
      SanskritObject passiveSanskritObject = passiveChanges.get(i);
      assertEquals(activeSanskritObject.getString(MODE), passiveSanskritObject.getString(MODE));
      assertEquals(activeSanskritObject.getLong(MUTATIVE_MESSAGE_COUNT), passiveSanskritObject.getLong(MUTATIVE_MESSAGE_COUNT));
      assertEquals(activeSanskritObject.getString(LATEST_CHANGE_UUID), passiveSanskritObject.getString(LATEST_CHANGE_UUID));
      if (activeSanskritObject.getString(LATEST_CHANGE_UUID) != null) {
        SanskritObject activeChangeObject = activeSanskritObject.getObject(activeSanskritObject.getString(LATEST_CHANGE_UUID));
        SanskritObject passiveChangeObject = passiveSanskritObject.getObject(passiveSanskritObject.getString(LATEST_CHANGE_UUID));
        assertEquals(activeChangeObject.getString(CHANGE_STATE), passiveChangeObject.getString(CHANGE_STATE));
        assertEquals(activeChangeObject.getLong(CHANGE_VERSION), passiveChangeObject.getLong(CHANGE_VERSION));
        assertEquals(activeChangeObject.getString(PREV_CHANGE_UUID), passiveChangeObject.getString(PREV_CHANGE_UUID));
        SanskritObject activeOpsObject = activeChangeObject.getObject(CHANGE_OPERATION);
        SanskritObject passiveOpsObject = passiveChangeObject.getObject(CHANGE_OPERATION);
        assertEquals(activeOpsObject.getString("type"), passiveOpsObject.getString("type"));
        assertEquals(activeOpsObject.getString("summary"), passiveOpsObject.getString("summary"));
      }
    }
  }

  private void verifyTopologies() throws Exception {
    // config repos written on disk should be the same
    assertThat(getUpcomingCluster("localhost", getNodePort(1, 1)), is(equalTo(getUpcomingCluster("localhost", getNodePort(1, 2)))));
    // runtime topology should be the same
    assertThat(getRuntimeCluster("localhost", getNodePort(1, 1)), is(equalTo(getRuntimeCluster("localhost", getNodePort(1, 2)))));
    // runtime topology should be the same as upcoming one
    assertThat(getRuntimeCluster("localhost", getNodePort(1, 1)), is(equalTo(getUpcomingCluster("localhost", getNodePort(1, 2)))));
  }

  private static List<SanskritObject> getChanges(Path pathToAppendLog) throws SanskritException {
    List<SanskritObject> res = new ArrayList<>();
    ObjectMapper objectMapper = Json.copyObjectMapper();
    new SanskritImpl(new FileBasedFilesystemDirectory(pathToAppendLog), objectMapper) {
      @Override
      public void onNewRecord(String timeStamp, String json) throws SanskritException {
        MutableSanskritObject mutableSanskritObject = new SanskritObjectImpl(objectMapper);
        JsonUtils.parse(objectMapper, json, mutableSanskritObject);
        res.add(mutableSanskritObject);
      }
    };
    return res;
  }
}
