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
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.exception.ErrorCode;
import com.tencent.polaris.api.exception.ServerCodes;
import com.tencent.polaris.api.plugin.skill.SkillDownloadRequest;
import com.tencent.polaris.api.plugin.skill.SkillDownloadResponse;
import com.tencent.polaris.api.plugin.skill.SkillListRequest;
import com.tencent.polaris.api.plugin.skill.SkillListResponse;
import com.tencent.polaris.api.plugin.skill.SkillResource;
import com.tencent.polaris.api.plugin.skill.SkillVersionInfo;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
class PolarisSkillRepositoryTest {

    @Mock private SkillAPI skillAPI;

    private PolarisSkillRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PolarisSkillRepository(skillAPI, "default");
    }

    @Test
    void constructorRejectsNullContext() {
        assertThrows(NullPointerException.class, () -> new PolarisSkillRepository(null));
    }

    @Test
    void constructorFromContextUsesSharedSkillApi() {
        PolarisContextManager context = mock(PolarisContextManager.class);
        when(context.skillAPI()).thenReturn(skillAPI);
        when(context.getNamespace()).thenReturn("prod");

        PolarisSkillRepository created = new PolarisSkillRepository(context);
        assertEquals("polaris:prod", created.getSource());
    }

    @Test
    void constructorRejectsNullSkillApi() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PolarisSkillRepository((SkillAPI) null, "default"));
    }

    @Test
    void getSkillRejectsBlankName() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> repository.getSkill("  "));
        assertEquals("Skill name cannot be null or empty", e.getMessage());
    }

    @Test
    void getSkillMapsNotFoundCode() throws Exception {
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.NOT_FOUND_RESOURCE);
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> repository.getSkill("missing"));
        assertEquals("Skill not found: missing", e.getMessage());
    }

    @Test
    void getSkillMapsNetworkError() throws Exception {
        when(skillAPI.downloadSkill(any()))
                .thenThrow(new PolarisException(ErrorCode.NETWORK_ERROR, "down"));

        RuntimeException e =
                assertThrows(RuntimeException.class, () -> repository.getSkill("sql-analysis"));
        assertEquals("Failed to load skill from Polaris: sql-analysis", e.getMessage());
    }

    @Test
    void getSkillDownloadsZipAndBuildsAgentSkill() throws Exception {
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(skillZip("sql-analysis", "Analyze SQL", "Run EXPLAIN", "refs/a.md", "hint"));
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        AgentSkill skill = repository.getSkill("sql-analysis");

        assertEquals("sql-analysis", skill.getName());
        assertEquals("Analyze SQL", skill.getDescription());
        assertEquals("Run EXPLAIN", skill.getSkillContent());
        assertEquals("hint", skill.getResource("refs/a.md"));
        assertEquals("polaris:default", skill.getSource());

        ArgumentCaptor<SkillDownloadRequest> captor = ArgumentCaptor.forClass(SkillDownloadRequest.class);
        verify(skillAPI).downloadSkill(captor.capture());
        assertEquals("sql-analysis", captor.getValue().getName());
        assertEquals("default", captor.getValue().getNamespace());
        assertEquals("zip", captor.getValue().getFormat());
    }

    @Test
    void getSkillWrapsFlatPolarisZipUnderSkillName() throws Exception {
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(flatSkillZip("fishtail-test", "A skill", "Body", "assets/ERRORS.md", "err"));
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        AgentSkill skill = repository.getSkill("fishtail-test");

        assertEquals("fishtail-test", skill.getName());
        assertEquals("A skill", skill.getDescription());
        assertEquals("Body", skill.getSkillContent());
        assertEquals("err", skill.getResource("assets/ERRORS.md"));
    }

    @Test
    void getSkillSkipsDirectoryEntriesInFlatZip() throws Exception {
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(flatZipWithDirectory("sql-analysis", "Analyze SQL", "Run EXPLAIN"));
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        AgentSkill skill = repository.getSkill("sql-analysis");
        assertEquals("sql-analysis", skill.getName());
        assertEquals("Run EXPLAIN", skill.getSkillContent());
    }

    @Test
    void getSkillLeavesMultiRootZipUnchanged() throws Exception {
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(multiRootZip());
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        assertThrows(IllegalArgumentException.class, () -> repository.getSkill("fishtail-test"));
    }

    @Test
    void getSkillSendsConfiguredVersion() throws Exception {
        repository = new PolarisSkillRepository(skillAPI, "default", "1.0.0");
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(skillZip("sql-analysis", "Analyze SQL", "Run EXPLAIN", null, (String) null));
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        repository.getSkill("sql-analysis");

        ArgumentCaptor<SkillDownloadRequest> captor = ArgumentCaptor.forClass(SkillDownloadRequest.class);
        verify(skillAPI).downloadSkill(captor.capture());
        assertEquals("1.0.0", captor.getValue().getVersion());
    }

    @Test
    void skillExistsFalseOnNotFound() throws Exception {
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.NOT_FOUND_RESOURCE);
        when(skillAPI.downloadSkill(any())).thenReturn(resp);
        assertFalse(repository.skillExists("missing"));
    }

    @Test
    void saveAndDeleteAreNoops() {
        assertFalse(repository.isWriteable());
        assertFalse(repository.save(List.of(), false));
        assertFalse(repository.delete("sql-analysis"));
    }

    @Test
    void getAllSkillsListsThenDownloadsEach() throws Exception {
        SkillListResponse list = new SkillListResponse();
        list.setCode(ServerCodes.EXECUTE_SUCCESS);
        SkillResource r = new SkillResource();
        r.setName("sql-analysis");
        SkillVersionInfo vi = new SkillVersionInfo();
        vi.setActiveVersion("1.0.0");
        r.setVersionInfo(vi);
        list.setResources(List.of(r));
        list.setTotal(1);
        when(skillAPI.listSkills(any())).thenReturn(list);

        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(skillZip("sql-analysis", "Analyze SQL", "Run EXPLAIN", null, (String) null));
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        List<AgentSkill> skills = repository.getAllSkills();
        assertEquals(1, skills.size());
        assertEquals("sql-analysis", skills.get(0).getName());
        assertEquals(List.of("sql-analysis"), repository.getAllSkillNames());

        repository.getAllSkills();
        verify(skillAPI, times(1)).listSkills(any());
        ArgumentCaptor<SkillDownloadRequest> captor = ArgumentCaptor.forClass(SkillDownloadRequest.class);
        verify(skillAPI, times(1)).downloadSkill(captor.capture());
        assertEquals("1.0.0", captor.getValue().getVersion());
    }

    @Test
    void getAllSkillsDownloadsEachListedActiveVersion() throws Exception {
        SkillListResponse list = new SkillListResponse();
        list.setCode(ServerCodes.EXECUTE_SUCCESS);
        list.setResources(List.of(
                listedSkill("sql-analysis", "1.0.0"),
                listedSkill("chart-rendering", "2.3.0")));
        list.setTotal(2);
        when(skillAPI.listSkills(any())).thenReturn(list);
        when(skillAPI.downloadSkill(any())).thenAnswer(inv -> {
            SkillDownloadRequest req = inv.getArgument(0);
            SkillDownloadResponse resp = new SkillDownloadResponse();
            resp.setCode(ServerCodes.EXECUTE_SUCCESS);
            resp.setZipContent(skillZip(req.getName(), "Desc", "Body", null, (String) null));
            return resp;
        });

        assertEquals(2, repository.getAllSkills().size());

        ArgumentCaptor<SkillDownloadRequest> captor = ArgumentCaptor.forClass(SkillDownloadRequest.class);
        verify(skillAPI, times(2)).downloadSkill(captor.capture());
        assertEquals("sql-analysis", captor.getAllValues().get(0).getName());
        assertEquals("1.0.0", captor.getAllValues().get(0).getVersion());
        assertEquals("chart-rendering", captor.getAllValues().get(1).getName());
        assertEquals("2.3.0", captor.getAllValues().get(1).getVersion());
    }

    @Test
    void getAllSkillsUsesDefaultListLimitWhenConfiguredZero() throws Exception {
        repository = new PolarisSkillRepository(skillAPI, "default", "", List.of(), 0, 100, 30_000L);
        SkillListResponse list = new SkillListResponse();
        list.setCode(ServerCodes.EXECUTE_SUCCESS);
        SkillResource resource = new SkillResource();
        resource.setName("sql-analysis");
        list.setResources(List.of(resource));
        list.setTotal(1);
        when(skillAPI.listSkills(any())).thenReturn(list);

        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(skillZip("sql-analysis", "Analyze SQL", "Run EXPLAIN", null, (String) null));
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        assertEquals(1, repository.getAllSkills().size());

        ArgumentCaptor<com.tencent.polaris.api.plugin.skill.SkillListRequest> captor =
                ArgumentCaptor.forClass(com.tencent.polaris.api.plugin.skill.SkillListRequest.class);
        verify(skillAPI).listSkills(captor.capture());
        assertEquals(50, captor.getValue().getLimit());
    }

    @Test
    void getAllSkillsUsesConfiguredNamesWithoutList() throws Exception {
        repository = new PolarisSkillRepository(skillAPI, "default", "", List.of("sql-analysis"), 50, 100, 30_000L);
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(skillZip("sql-analysis", "Analyze SQL", "Run EXPLAIN", null, (String) null));
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        List<AgentSkill> skills = repository.getAllSkills();
        assertEquals(1, skills.size());
        verify(skillAPI, never()).listSkills(any());
    }

    @Test
    void getAllSkillsWithConfiguredNamesReusesCacheWithinInterval() throws Exception {
        repository = new PolarisSkillRepository(skillAPI, "default", "", List.of("sql-analysis"), 50, 100, 30_000L);
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(skillZip("sql-analysis", "Analyze SQL", "Run EXPLAIN", null, (String) null));
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        assertEquals(1, repository.getAllSkills().size());
        assertEquals(1, repository.getAllSkills().size());
        verify(skillAPI, times(1)).downloadSkill(any());
        verify(skillAPI, never()).listSkills(any());
    }

    @Test
    void getAllSkillsWithConfiguredNamesReusesZipCacheWhenVersionUnchanged() throws Exception {
        repository = new PolarisSkillRepository(skillAPI, "default", "", List.of("sql-analysis"), 50, 100, 0L);
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(skillZip("sql-analysis", "Analyze SQL", "Run EXPLAIN", null, (String) null));
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        assertEquals(1, repository.getAllSkills().size());
        assertEquals(1, repository.getAllSkills().size());
        verify(skillAPI, times(1)).downloadSkill(any());
        verify(skillAPI, never()).listSkills(any());
    }

    @Test
    void fromFactoryBindsContextNamespaceAndVersion() {
        PolarisContextManager context = mock(PolarisContextManager.class);
        when(context.skillAPI()).thenReturn(skillAPI);
        when(context.getNamespace()).thenReturn("prod");

        assertEquals("polaris:prod", PolarisSkillRepository.from(context).getSource());
        assertEquals("polaris:prod", PolarisSkillRepository.from(context, "1.2.3").getSource());
    }

    @Test
    void constructorNormalizesBlankInputs() {
        PolarisSkillRepository created =
                new PolarisSkillRepository(skillAPI, "  ", null, null, 0, 0, -1L);
        assertEquals("polaris:default", created.getSource());
        AgentSkillRepositoryInfo info = created.getRepositoryInfo();
        assertEquals("polaris", info.getType());
        assertEquals("namespace:default", info.getLocation());
        assertFalse(info.isWritable());
    }

    @Test
    void getSkillRejectsNullName() {
        assertThrows(IllegalArgumentException.class, () -> repository.getSkill(null));
    }

    @Test
    void getSkillTreatsEmptyZipAsNotFound() throws Exception {
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(new byte[0]);
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> repository.getSkill("missing"));
        assertEquals("Skill not found: missing", e.getMessage());
    }

    @Test
    void skillExistsTrueAfterDownload() throws Exception {
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(skillZip("sql-analysis", "Analyze SQL", "Run EXPLAIN", null, (String) null));
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        assertFalse(repository.skillExists(" "));
        assertTrue(repository.skillExists("sql-analysis"));
    }

    @Test
    void skillExistsFalseWhenDownloadFails() throws Exception {
        when(skillAPI.downloadSkill(any()))
                .thenThrow(new PolarisException(ErrorCode.NETWORK_ERROR, "down"));
        assertFalse(repository.skillExists("sql-analysis"));
    }

    @Test
    void setWriteableIsIgnored() {
        repository.setWriteable(true);
        assertFalse(repository.isWriteable());
    }

    @Test
    void getAllSkillsPaginatesUntilMaxSkills() throws Exception {
        repository = new PolarisSkillRepository(skillAPI, "default", "", List.of(), 2, 2, 0L);
        when(skillAPI.listSkills(any())).thenAnswer(inv -> {
            SkillListRequest req = inv.getArgument(0);
            SkillListResponse page = new SkillListResponse();
            page.setCode(ServerCodes.EXECUTE_SUCCESS);
            page.setTotal(4);
            if (req.getOffset() == 0) {
                SkillResource first = new SkillResource();
                first.setName("first");
                page.setResources(List.of(blankResource(), first));
            } else {
                SkillResource second = new SkillResource();
                second.setName("second");
                SkillResource third = new SkillResource();
                third.setName("third");
                page.setResources(List.of(second, third));
            }
            return page;
        });

        assertEquals(List.of("first", "second"), repository.getAllSkillNames());
        verify(skillAPI, times(2)).listSkills(any());
    }

    @Test
    void getAllSkillsWrapsListErrors() throws Exception {
        doThrow(new PolarisException(ErrorCode.NETWORK_ERROR, "down"))
                .when(skillAPI).listSkills(any());
        RuntimeException listed =
                assertThrows(RuntimeException.class, () -> repository.getAllSkillNames());
        assertEquals("Failed to list skills from Polaris", listed.getMessage());

        SkillListResponse failed = new SkillListResponse();
        failed.setCode(500);
        failed.setInfo("denied");
        doReturn(failed).when(skillAPI).listSkills(any());
        RuntimeException denied =
                assertThrows(RuntimeException.class, () -> repository.getAllSkillNames());
        assertEquals("Failed to list skills from Polaris: denied", denied.getMessage());
    }

    @Test
    void getSkillKeepsInvalidZipBytes() throws Exception {
        SkillDownloadResponse corrupt = new SkillDownloadResponse();
        corrupt.setCode(ServerCodes.EXECUTE_SUCCESS);
        corrupt.setZipContent(new byte[] {1, 2, 3});
        when(skillAPI.downloadSkill(any())).thenReturn(corrupt);
        assertThrows(RuntimeException.class, () -> repository.getSkill("sql-analysis"));

        SkillDownloadResponse absolute = new SkillDownloadResponse();
        absolute.setCode(ServerCodes.EXECUTE_SUCCESS);
        absolute.setZipContent(namedEntryZip("/SKILL.md", "x"));
        when(skillAPI.downloadSkill(any())).thenReturn(absolute);
        assertThrows(RuntimeException.class, () -> repository.getSkill("sql-analysis"));

        SkillDownloadResponse parent = new SkillDownloadResponse();
        parent.setCode(ServerCodes.EXECUTE_SUCCESS);
        parent.setZipContent(namedEntryZip("../SKILL.md", "x"));
        when(skillAPI.downloadSkill(any())).thenReturn(parent);
        assertThrows(RuntimeException.class, () -> repository.getSkill("sql-analysis"));
    }

    @Test
    void getAllSkillNamesReusesListInsideRefreshLock() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(skillAPI.listSkills(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                holding.countDown();
                release.await(3, TimeUnit.SECONDS);
            }
            SkillListResponse list = new SkillListResponse();
            list.setCode(ServerCodes.EXECUTE_SUCCESS);
            list.setResources(List.of());
            list.setTotal(0);
            return list;
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
        verify(skillAPI, times(1)).listSkills(any());
    }

    @Test
    void closeDoesNotDestroySkillApi() {
        repository.close();
        verify(skillAPI, never()).destroy();
        verify(skillAPI, never()).close();
    }

    @Test
    void getAllSkillsSkipsSingleDownloadFailure() throws Exception {
        SkillListResponse list = new SkillListResponse();
        list.setCode(ServerCodes.EXECUTE_SUCCESS);
        SkillResource good = new SkillResource();
        good.setName("ok-skill");
        SkillResource bad = new SkillResource();
        bad.setName("bad-skill");
        list.setResources(List.of(good, bad));
        when(skillAPI.listSkills(any())).thenReturn(list);
        when(skillAPI.downloadSkill(any())).thenAnswer(inv -> {
            SkillDownloadRequest req = inv.getArgument(0);
            if ("bad-skill".equals(req.getName())) {
                throw new PolarisException(ErrorCode.NETWORK_ERROR, "down");
            }
            SkillDownloadResponse resp = new SkillDownloadResponse();
            resp.setCode(ServerCodes.EXECUTE_SUCCESS);
            resp.setZipContent(skillZip(req.getName(), "Desc", "Body", null, (String) null));
            return resp;
        });

        List<AgentSkill> skills = repository.getAllSkills();
        assertEquals(1, skills.size());
        assertEquals("ok-skill", skills.get(0).getName());
    }

    @Test
    void getAllSkillsReusesEmptyListWithinRefreshInterval() throws Exception {
        SkillListResponse list = new SkillListResponse();
        list.setCode(ServerCodes.EXECUTE_SUCCESS);
        list.setResources(List.of());
        list.setTotal(0);
        when(skillAPI.listSkills(any())).thenReturn(list);

        assertEquals(List.of(), repository.getAllSkills());
        assertEquals(List.of(), repository.getAllSkills());
        verify(skillAPI, times(1)).listSkills(any());
    }

    private static SkillResource listedSkill(String name, String activeVersion) {
        SkillResource resource = new SkillResource();
        resource.setName(name);
        SkillVersionInfo versionInfo = new SkillVersionInfo();
        versionInfo.setActiveVersion(activeVersion);
        resource.setVersionInfo(versionInfo);
        return resource;
    }

    private static byte[] skillZip(
            String name, String description, String body, String extraPath, String extraContent)
            throws IOException {
        String md = "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            zout.putNextEntry(new ZipEntry(name + "/SKILL.md"));
            zout.write(md.getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
            if (extraPath != null) {
                zout.putNextEntry(new ZipEntry(name + "/" + extraPath));
                zout.write(extraContent.getBytes(StandardCharsets.UTF_8));
                zout.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static byte[] flatSkillZip(
            String name, String description, String body, String extraPath, String extraContent)
            throws IOException {
        String md = "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            zout.putNextEntry(new ZipEntry("SKILL.md"));
            zout.write(md.getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
            zout.putNextEntry(new ZipEntry("README.md"));
            zout.write("readme".getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
            if (extraPath != null) {
                zout.putNextEntry(new ZipEntry(extraPath));
                zout.write(extraContent.getBytes(StandardCharsets.UTF_8));
                zout.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static SkillResource blankResource() {
        SkillResource resource = new SkillResource();
        resource.setName("  ");
        return resource;
    }

    private static byte[] flatZipWithDirectory(String name, String description, String body)
            throws IOException {
        String md = "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            zout.putNextEntry(new ZipEntry("assets/"));
            zout.closeEntry();
            zout.putNextEntry(new ZipEntry("SKILL.md"));
            zout.write(md.getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] namedEntryZip(String entryName, String content) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            zout.putNextEntry(new ZipEntry(entryName));
            zout.write(content.getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] multiRootZip() throws IOException {
        String md = "---\nname: fishtail-test\ndescription: desc\n---\nBody";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            zout.putNextEntry(new ZipEntry("root-a/SKILL.md"));
            zout.write(md.getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
            zout.putNextEntry(new ZipEntry("root-b/readme.txt"));
            zout.write("readme".getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
        }
        return bos.toByteArray();
    }
}
