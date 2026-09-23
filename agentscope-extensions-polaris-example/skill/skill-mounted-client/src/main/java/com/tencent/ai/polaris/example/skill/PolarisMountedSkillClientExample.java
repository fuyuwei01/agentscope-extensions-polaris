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

package com.tencent.ai.polaris.example.skill;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.skill.PolarisMountedSkillRepository;
import java.time.LocalTime;
import java.util.List;

/**
 * Polls skills mounted on a Polaris service ({@code Service.extended_metadata})
 * through {@link PolarisMountedSkillRepository} until interrupted.
 *
 * <p>Environment / system properties:
 * <ul>
 *   <li>{@code POLARIS_ADDRESS} — discovery/registry, default {@code 127.0.0.1:8091}
 *   <li>{@code POLARIS_SKILL_ADDRESS} — SkillAPI, default {@code 127.0.0.1:8094}
 *   <li>{@code POLARIS_SERVICE} / {@code -Dservice.name} — agent service name
 *       (must match the A2A registration name), default {@code demo-agent}
 *   <li>{@code POLARIS_POLL_INTERVAL_MS} / {@code -Dpoll.interval.ms} — poll interval,
 *       default {@code 2000}
 * </ul>
 */
public final class PolarisMountedSkillClientExample {

    private static final long DEFAULT_POLL_INTERVAL_MS = 2000L;

    public static void main(String[] args) throws Exception {
        String address = firstNonBlank(System.getenv("POLARIS_ADDRESS"), "127.0.0.1:8091");
        String skillAddress = firstNonBlank(System.getenv("POLARIS_SKILL_ADDRESS"), "127.0.0.1:8094");
        String serviceName = firstNonBlank(
                System.getenv("POLARIS_SERVICE"),
                System.getProperty("service.name"),
                "demo-agent");
        long pollIntervalMs = parsePollIntervalMs();

        try (PolarisContextManager context = PolarisContextManager.fromAddress(address, skillAddress);
                PolarisMountedSkillRepository repo =
                        PolarisMountedSkillRepository.from(context, serviceName)) {
            System.out.println(
                    "polling mounted skills service=" + serviceName
                            + " discovery=" + address
                            + " skillApi=" + skillAddress
                            + " source=" + repo.getSource()
                            + " intervalMs=" + pollIntervalMs);
            pollMountedSkills(repo, pollIntervalMs);
        }
    }

    private static void pollMountedSkills(PolarisMountedSkillRepository repo, long pollIntervalMs)
            throws InterruptedException {
        List<String> lastNames = null;
        int round = 0;
        while (true) {
            round++;
            try {
                List<String> names = repo.getAllSkillNames();
                boolean changed = lastNames == null || !lastNames.equals(names);
                System.out.println(
                        LocalTime.now()
                                + " round=" + round
                                + (changed ? " changed" : "")
                                + " mounted=" + names);
                lastNames = names;
                for (String name : names) {
                    repo.getSkill(name);
                    System.out.println(
                            LocalTime.now()
                                    + " round=" + round
                                    + " skill=" + name);
                }
            } catch (RuntimeException e) {
                System.out.println(
                        LocalTime.now()
                                + " round=" + round
                                + " poll failed: " + e.getMessage());
            }
            Thread.sleep(pollIntervalMs);
        }
    }

    private static long parsePollIntervalMs() {
        String raw = firstNonBlank(
                System.getenv("POLARIS_POLL_INTERVAL_MS"),
                System.getProperty("poll.interval.ms"));
        if (raw.isEmpty()) {
            return DEFAULT_POLL_INTERVAL_MS;
        }
        long interval = Long.parseLong(raw);
        if (interval <= 0) {
            throw new IllegalArgumentException("poll interval must be positive: " + raw);
        }
        return interval;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private PolarisMountedSkillClientExample() {}
}
