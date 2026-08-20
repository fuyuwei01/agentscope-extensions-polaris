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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tencent.polaris.ai.api.core.SkillAPI;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.exception.ErrorCode;
import com.tencent.polaris.api.exception.ServerCodes;
import com.tencent.polaris.api.plugin.skill.SkillDownloadRequest;
import com.tencent.polaris.api.plugin.skill.SkillDownloadResponse;
import com.tencent.polaris.api.plugin.skill.SkillListResponse;
import com.tencent.polaris.api.plugin.skill.SkillResource;
import com.tencent.polaris.api.plugin.skill.SkillVersionInfo;
import io.agentscope.core.skill.AgentSkill;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
    void constructorRejectsNullSkillApi() {
        assertThrows(IllegalArgumentException.class, () -> new PolarisSkillRepository(null, "default"));
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
        verify(skillAPI, times(1)).downloadSkill(any());
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
    void getAllSkillsWithConfiguredNamesRedownloadsWhenRefreshIntervalIsZero() throws Exception {
        repository = new PolarisSkillRepository(skillAPI, "default", "", List.of("sql-analysis"), 50, 100, 0L);
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(skillZip("sql-analysis", "Analyze SQL", "Run EXPLAIN", null, (String) null));
        when(skillAPI.downloadSkill(any())).thenReturn(resp);

        assertEquals(1, repository.getAllSkills().size());
        assertEquals(1, repository.getAllSkills().size());
        verify(skillAPI, times(2)).downloadSkill(any());
        verify(skillAPI, never()).listSkills(any());
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
}
