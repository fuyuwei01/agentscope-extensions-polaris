/*
 * Tencent is pleased to support the open source community by making agentscope-extensions-polaris available.
 *
 * Copyright (C) 2026 Tencent. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.ai.polaris.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.polaris.ai.api.core.SkillAPI;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.exception.ErrorCode;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.exception.ServerCodes;
import com.tencent.polaris.api.plugin.skill.SkillDownloadRequest;
import com.tencent.polaris.api.plugin.skill.SkillDownloadResponse;
import com.tencent.polaris.api.pojo.DefaultServiceInstances;
import com.tencent.polaris.api.pojo.ExtendedMetadata;
import com.tencent.polaris.api.pojo.ServiceInstances;
import com.tencent.polaris.api.pojo.ServiceKey;
import com.tencent.polaris.api.rpc.GetAllInstancesRequest;
import com.tencent.polaris.api.rpc.InstancesResponse;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PolarisMountedSkillRepositoryTest {

    @Mock private SkillAPI skillAPI;
    @Mock private ConsumerAPI consumerAPI;

    private PolarisMountedSkillRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PolarisMountedSkillRepository(
                skillAPI, consumerAPI, "default", "demo-agent", "");
    }

    @Test
    void constructorRejectsNullContext() {
        assertThrows(
                NullPointerException.class,
                () -> new PolarisMountedSkillRepository(null, "demo-agent"));
    }

    @Test
    void constructorFromContextUsesSharedApis() {
        PolarisContextManager context = mock(PolarisContextManager.class);
        when(context.skillAPI()).thenReturn(skillAPI);
        when(context.consumerAPI()).thenReturn(consumerAPI);
        when(context.getNamespace()).thenReturn("default");

        PolarisMountedSkillRepository created =
                new PolarisMountedSkillRepository(context, "demo-agent");
        assertEquals("polaris-mounted:default/demo-agent", created.getSource());
    }

    @Test
    void constructorRejectsNullConsumerApi() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PolarisMountedSkillRepository(
                        skillAPI, null, "default", "demo-agent", ""));
    }

    @Test
    void constructorFromContextRejectsBlankServiceName() {
        PolarisContextManager context = mock(PolarisContextManager.class);
        when(context.skillAPI()).thenReturn(skillAPI);
        assertThrows(
                IllegalArgumentException.class,
                () -> new PolarisMountedSkillRepository(context, "  ", ""));
    }

    @Test
    void constructorRejectsBlankServiceName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PolarisMountedSkillRepository(
                        skillAPI, consumerAPI, "default", "  ", ""));
    }

    @Test
    void sourceAndRepositoryInfoUseMountedIdentity() {
        assertEquals("polaris-mounted:default/demo-agent", repository.getSource());
        AgentSkillRepositoryInfo info = repository.getRepositoryInfo();
        assertEquals("polaris-mounted", info.getType());
        assertEquals("namespace:default/demo-agent", info.getLocation());
        assertFalse(info.isWritable());
        assertFalse(repository.isWriteable());
    }

    @Test
    void getAllSkillNamesReturnsMountedNamesWithoutDownloading() {
        when(consumerAPI.getAllInstances(any())).thenReturn(serviceWithSkills("sql-analysis"));

        assertEquals(List.of("sql-analysis"), repository.getAllSkillNames());
        verify(skillAPI, never()).downloadSkill(any());
        verify(skillAPI, never()).listSkills(any());

        ArgumentCaptor<GetAllInstancesRequest> captor =
                ArgumentCaptor.forClass(GetAllInstancesRequest.class);
        verify(consumerAPI).getAllInstances(captor.capture());
        assertEquals("default", captor.getValue().getNamespace());
        assertEquals("demo-agent", captor.getValue().getService());
    }

    @Test
    void getAllSkillNamesFallsBackToSkillIdWhenNameBlank() {
        ExtendedMetadata meta = ExtendedMetadata.builder()
                .type(ExtendedMetadata.ExtendedMetadataType.SKILL)
                .agentSkill(com.tencent.polaris.api.pojo.AgentSkill.builder()
                        .id("default:sql-analysis")
                        .build())
                .build();
        when(consumerAPI.getAllInstances(any())).thenReturn(serviceWithMetadata(meta));

        assertEquals(List.of("sql-analysis"), repository.getAllSkillNames());
    }

    @Test
    void getAllSkillNamesDeduplicatesSkillNames() {
        when(consumerAPI.getAllInstances(any()))
                .thenReturn(serviceWithSkills("sql-analysis", "sql-analysis"));

        assertEquals(List.of("sql-analysis"), repository.getAllSkillNames());
    }

    @Test
    void getAllSkillNamesEmptyWhenServiceMissing() {
        when(consumerAPI.getAllInstances(any())).thenReturn(serviceWithMetadata());

        assertEquals(List.of(), repository.getAllSkillNames());
        verify(skillAPI, never()).downloadSkill(any());
    }

    @Test
    void getAllSkillNamesSplitsNamespacePrefix() {
        when(consumerAPI.getAllInstances(any()))
                .thenReturn(serviceWithMountedNames("default:sql-analysis"));

        assertEquals(List.of("sql-analysis"), repository.getAllSkillNames());
    }

    @Test
    void getAllSkillNamesKeepsColonsInsideSkillName() {
        when(consumerAPI.getAllInstances(any()))
                .thenReturn(serviceWithMountedNames("default:svg:architecture:diagram"));

        assertEquals(List.of("svg:architecture:diagram"), repository.getAllSkillNames());
    }

    @Test
    void getAllSkillNamesSkipsDifferentNamespace() {
        when(consumerAPI.getAllInstances(any()))
                .thenReturn(serviceWithMountedNames(
                        "default:sql-analysis", "prod:chart-rendering"));

        assertEquals(List.of("sql-analysis"), repository.getAllSkillNames());
    }

    @Test
    void getAllSkillNamesSkipsNameWithoutNamespacePrefix() {
        when(consumerAPI.getAllInstances(any()))
                .thenReturn(serviceWithMountedNames("sql-analysis"));

        assertEquals(List.of(), repository.getAllSkillNames());
    }

    @Test
    void getSkillDownloadsSkillNameWithColons() throws Exception {
        when(consumerAPI.getAllInstances(any()))
                .thenReturn(serviceWithMountedNames("default:svg:architecture:diagram"));
        when(skillAPI.downloadSkill(any()))
                .thenReturn(successZip("svg:architecture:diagram", "Draw SVG", "Use mermaid"));

        AgentSkill skill = repository.getSkill("svg:architecture:diagram");

        assertEquals("svg:architecture:diagram", skill.getName());
        ArgumentCaptor<SkillDownloadRequest> captor = ArgumentCaptor.forClass(SkillDownloadRequest.class);
        verify(skillAPI).downloadSkill(captor.capture());
        assertEquals("svg:architecture:diagram", captor.getValue().getName());
        assertEquals("default", captor.getValue().getNamespace());
    }

    @Test
    void getSkillRejectsUnmountedName() {
        when(consumerAPI.getAllInstances(any())).thenReturn(serviceWithSkills("sql-analysis"));

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> repository.getSkill("other-skill"));
        assertEquals("Skill not found: other-skill", e.getMessage());
        verify(skillAPI, never()).downloadSkill(any());
    }

    @Test
    void getSkillDownloadsMountedSkill() throws Exception {
        when(consumerAPI.getAllInstances(any())).thenReturn(serviceWithSkills("sql-analysis"));
        when(skillAPI.downloadSkill(any())).thenReturn(successZip("sql-analysis", "Analyze SQL", "Run EXPLAIN"));

        AgentSkill skill = repository.getSkill("sql-analysis");

        assertEquals("sql-analysis", skill.getName());
        assertEquals("Analyze SQL", skill.getDescription());
        assertEquals("Run EXPLAIN", skill.getSkillContent());
        assertEquals("polaris-mounted:default/demo-agent", skill.getSource());
        assertSame(skill, repository.getSkill("sql-analysis"));
        verify(skillAPI, times(1)).downloadSkill(any());

        ArgumentCaptor<SkillDownloadRequest> captor = ArgumentCaptor.forClass(SkillDownloadRequest.class);
        verify(skillAPI).downloadSkill(captor.capture());
        assertEquals("sql-analysis", captor.getValue().getName());
        assertEquals("default", captor.getValue().getNamespace());
        assertEquals("zip", captor.getValue().getFormat());
    }

    @Test
    void getSkillSendsConfiguredVersion() throws Exception {
        repository = new PolarisMountedSkillRepository(
                skillAPI, consumerAPI, "default", "demo-agent", "1.0.0");
        when(consumerAPI.getAllInstances(any())).thenReturn(serviceWithSkills("sql-analysis"));
        when(skillAPI.downloadSkill(any())).thenReturn(successZip("sql-analysis", "Analyze SQL", "Run EXPLAIN"));

        repository.getSkill("sql-analysis");

        ArgumentCaptor<SkillDownloadRequest> captor = ArgumentCaptor.forClass(SkillDownloadRequest.class);
        verify(skillAPI).downloadSkill(captor.capture());
        assertEquals("1.0.0", captor.getValue().getVersion());
    }

    @Test
    void getSkillUsesVersionFromMountedMetadata() throws Exception {
        repository = new PolarisMountedSkillRepository(
                skillAPI, consumerAPI, "default", "demo-agent", "1.0.0");
        when(consumerAPI.getAllInstances(any()))
                .thenReturn(serviceWithSkill("sql-analysis", "2.3.0"));
        when(skillAPI.downloadSkill(any())).thenReturn(successZip("sql-analysis", "Analyze SQL", "Run EXPLAIN"));

        repository.getSkill("sql-analysis");

        ArgumentCaptor<SkillDownloadRequest> captor = ArgumentCaptor.forClass(SkillDownloadRequest.class);
        verify(skillAPI).downloadSkill(captor.capture());
        assertEquals("2.3.0", captor.getValue().getVersion());
    }

    @Test
    void getAllSkillsUsePerSkillMountedVersions() throws Exception {
        when(consumerAPI.getAllInstances(any())).thenReturn(serviceWithMetadata(
                skillMetadata("default:sql-analysis", "2.3.0"),
                skillMetadata("default:chart-rendering", "0.9.1")));
        when(skillAPI.downloadSkill(any())).thenAnswer(inv -> {
            SkillDownloadRequest req = inv.getArgument(0);
            return successZip(req.getName(), req.getName() + " desc", "body");
        });

        assertEquals(2, repository.getAllSkills().size());

        ArgumentCaptor<SkillDownloadRequest> captor = ArgumentCaptor.forClass(SkillDownloadRequest.class);
        verify(skillAPI, times(2)).downloadSkill(captor.capture());
        assertEquals("2.3.0", captor.getAllValues().get(0).getVersion());
        assertEquals("0.9.1", captor.getAllValues().get(1).getVersion());
    }

    @Test
    void getAllSkillsReturnsMountedSkillsOnly() throws Exception {
        when(consumerAPI.getAllInstances(any()))
                .thenReturn(serviceWithSkills("sql-analysis", "chart-rendering"));
        when(skillAPI.downloadSkill(any())).thenAnswer(inv -> {
            SkillDownloadRequest req = inv.getArgument(0);
            return successZip(req.getName(), req.getName() + " desc", "body");
        });

        List<AgentSkill> skills = repository.getAllSkills();

        assertEquals(2, skills.size());
        assertEquals("sql-analysis", skills.get(0).getName());
        assertEquals("chart-rendering", skills.get(1).getName());
        verify(skillAPI, never()).listSkills(any());
    }

    @Test
    void getAllSkillsAndGetSkillShareZipCache() throws Exception {
        when(consumerAPI.getAllInstances(any())).thenReturn(serviceWithSkills("sql-analysis"));
        when(skillAPI.downloadSkill(any()))
                .thenReturn(successZip("sql-analysis", "Analyze SQL", "Run EXPLAIN"));

        AgentSkill listed = repository.getAllSkills().get(0);
        AgentSkill loaded = repository.getSkill("sql-analysis");

        assertSame(listed, loaded);
        verify(skillAPI, times(1)).downloadSkill(any());
    }

    @Test
    void skillExistsOnlyForMountedNames() {
        when(consumerAPI.getAllInstances(any())).thenReturn(serviceWithSkills("sql-analysis"));

        assertTrue(repository.skillExists("sql-analysis"));
        assertFalse(repository.skillExists("other-skill"));
        verify(skillAPI, never()).downloadSkill(any());
    }

    @Test
    void fromFactoryBindsContext() {
        PolarisContextManager context = mock(PolarisContextManager.class);
        when(context.skillAPI()).thenReturn(skillAPI);
        when(context.consumerAPI()).thenReturn(consumerAPI);
        when(context.getNamespace()).thenReturn("prod");

        assertEquals(
                "polaris-mounted:prod/demo-agent",
                PolarisMountedSkillRepository.from(context, "demo-agent").getSource());
        assertEquals(
                "polaris-mounted:prod/demo-agent",
                PolarisMountedSkillRepository.from(context, "demo-agent", null).getSource());
    }

    @Test
    void getSkillRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> repository.getSkill(" "));
        assertFalse(repository.skillExists(null));
        assertFalse(repository.skillExists(" "));
    }

    @Test
    void getAllSkillNamesEmptyWhenLookupFails() throws Exception {
        doThrow(new PolarisException(ErrorCode.NETWORK_ERROR, "down"))
                .when(consumerAPI).getAllInstances(any());
        RuntimeException e =
                assertThrows(RuntimeException.class, () -> repository.getAllSkillNames());
        assertEquals("Failed to load mounted skills from Polaris: demo-agent", e.getMessage());

        doReturn(null).when(consumerAPI).getAllInstances(any());
        assertEquals(List.of(), repository.getAllSkillNames());
    }

    @Test
    void getAllSkillNamesSkipsNonSkillMetadata() {
        ExtendedMetadata other = ExtendedMetadata.builder().build();
        ExtendedMetadata emptySkill = ExtendedMetadata.builder()
                .type(ExtendedMetadata.ExtendedMetadataType.SKILL)
                .build();
        ExtendedMetadata blankIdentity = ExtendedMetadata.builder()
                .type(ExtendedMetadata.ExtendedMetadataType.SKILL)
                .agentSkill(com.tencent.polaris.api.pojo.AgentSkill.builder().build())
                .build();
        when(consumerAPI.getAllInstances(any())).thenReturn(serviceWithMetadata(
                null, other, emptySkill, blankIdentity, skillMetadata(":", ""),
                skillMetadata("default:", ""), skillMetadata(":sql", "")));

        assertEquals(List.of(), repository.getAllSkillNames());
    }

    @Test
    void getAllSkillsSkipsDownloadFailure() throws Exception {
        when(consumerAPI.getAllInstances(any()))
                .thenReturn(serviceWithSkills("sql-analysis", "chart-rendering"));
        when(skillAPI.downloadSkill(any())).thenAnswer(inv -> {
            com.tencent.polaris.api.plugin.skill.SkillDownloadRequest req = inv.getArgument(0);
            if ("chart-rendering".equals(req.getName())) {
                throw new PolarisException(ErrorCode.NETWORK_ERROR, "down");
            }
            return successZip(req.getName(), "Analyze SQL", "Run EXPLAIN");
        });

        List<AgentSkill> skills = repository.getAllSkills();
        assertEquals(1, skills.size());
        assertEquals("sql-analysis", skills.get(0).getName());
        verify(consumerAPI, times(1)).getAllInstances(any());
    }

    @Test
    void getAllSkillNamesReusesMountedCacheInsideRefreshLock() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(consumerAPI.getAllInstances(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                holding.countDown();
                release.await(3, TimeUnit.SECONDS);
            }
            return serviceWithSkills("sql-analysis");
        });
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                start.await();
                repository.getAllSkillNames();
                return null;
            });
            pool.submit(() -> {
                start.await();
                repository.getAllSkillNames();
                return null;
            });
            holding.await(3, TimeUnit.SECONDS);
            release.countDown();
        } finally {
            pool.shutdown();
            pool.awaitTermination(3, TimeUnit.SECONDS);
        }
        verify(consumerAPI, times(1)).getAllInstances(any());
    }

    @Test
    void writesRemainNoOps() {
        AgentSkill skill = new AgentSkill("local-skill", "Local desc", "Local body", java.util.Map.of());
        assertFalse(repository.save(List.of(skill), true));
        assertFalse(repository.delete("local-skill"));
        repository.setWriteable(true);
        assertFalse(repository.isWriteable());
    }

    @Test
    void closeDoesNotDestroySharedApis() {
        repository.close();
        verify(skillAPI, never()).destroy();
        verify(skillAPI, never()).close();
        verify(consumerAPI, never()).close();
        verify(consumerAPI, never()).destroy();
    }

    private static InstancesResponse serviceWithSkills(String... names) {
        ExtendedMetadata[] metas = new ExtendedMetadata[names.length];
        for (int i = 0; i < names.length; i++) {
            metas[i] = skillMetadata("default:" + names[i], "");
        }
        return serviceWithMetadata(metas);
    }

    private static InstancesResponse serviceWithMountedNames(String... mountedNames) {
        ExtendedMetadata[] metas = new ExtendedMetadata[mountedNames.length];
        for (int i = 0; i < mountedNames.length; i++) {
            metas[i] = skillMetadata(mountedNames[i], "");
        }
        return serviceWithMetadata(metas);
    }

    private static InstancesResponse serviceWithSkill(String name, String version) {
        return serviceWithMetadata(skillMetadata("default:" + name, version));
    }

    private static ExtendedMetadata skillMetadata(String name, String version) {
        return ExtendedMetadata.builder()
                .type(ExtendedMetadata.ExtendedMetadataType.SKILL)
                .agentSkill(com.tencent.polaris.api.pojo.AgentSkill.builder()
                        .name(name)
                        .version(version)
                        .build())
                .build();
    }

    /**
     * Mounted skills are read from the service instances: {@code extended_metadata} only travels
     * on {@code ConsumerAPI.getAllInstances} responses.
     */
    private static InstancesResponse serviceWithMetadata(ExtendedMetadata... metas) {
        List<ExtendedMetadata> extendedMetadata = Arrays.asList(metas);
        ServiceInstances instances =
                new DefaultServiceInstances(new ServiceKey("default", "demo-agent"), List.of()) {
                    @Override
                    public List<ExtendedMetadata> getExtendedMetadata() {
                        return extendedMetadata;
                    }
                };
        return new InstancesResponse(instances, null, null);
    }

    private static SkillDownloadResponse successZip(String name, String description, String body)
            throws IOException {
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(skillZip(name, description, body));
        return resp;
    }

    private static byte[] skillZip(String name, String description, String body) throws IOException {
        String md = "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            zout.putNextEntry(new ZipEntry(name + "/SKILL.md"));
            zout.write(md.getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
        }
        return bos.toByteArray();
    }
}
