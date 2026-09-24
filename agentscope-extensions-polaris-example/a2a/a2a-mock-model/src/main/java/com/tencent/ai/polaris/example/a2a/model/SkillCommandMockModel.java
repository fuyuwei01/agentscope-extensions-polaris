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

package com.tencent.ai.polaris.example.a2a.model;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Flux;

/**
 * Offline stand-in for a chat model that answers the two skill commands deterministically.
 *
 * <p>Reads the skill catalog that {@code SkillHook} injects into the system message on every
 * {@code PreCallEvent}, so it only ever reports skills that really reached the model:
 * <ul>
 *   <li>{@code /skill-list} — renders the injected catalog as text
 *   <li>{@code /skill <name>} — emits a {@code load_skill_through_path} tool call, then returns
 *       the tool output (the skill's SKILL.md) on the following turn
 * </ul>
 * Any other input returns a short usage hint. Requires {@code maxIters >= 2} so the agent can
 * run the tool call and come back for the final answer.
 */
public final class SkillCommandMockModel implements Model {

    private static final String LOAD_SKILL_TOOL = "load_skill_through_path";
    private static final String SKILL_MARKDOWN = "SKILL.md";
    private static final String LIST_COMMAND = "/skill-list";
    private static final String LOAD_COMMAND = "/skill";

    private static final Pattern AVAILABLE_SKILLS =
            Pattern.compile("<available_skills>(.*?)</available_skills>", Pattern.DOTALL);
    private static final Pattern SKILL_BLOCK =
            Pattern.compile("<skill>(.*?)</skill>", Pattern.DOTALL);

    private static final String USAGE = "Mock model (no LLM configured). Supported commands:\n"
            + "  " + LIST_COMMAND + "     list the skills pulled from Polaris\n"
            + "  " + LOAD_COMMAND + " <name>   print the SKILL.md of one skill";

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        String toolOutput = lastToolOutput(messages);
        if (toolOutput != null) {
            return Flux.just(textResponse(toolOutput));
        }
        List<CatalogEntry> catalog = parseCatalog(systemText(messages));
        String input = lastUserText(messages).trim();
        if (LIST_COMMAND.equalsIgnoreCase(input)) {
            return Flux.just(textResponse(renderCatalog(catalog)));
        }
        if (isLoadCommand(input)) {
            return Flux.just(loadSkill(input.substring(LOAD_COMMAND.length()).trim(), catalog));
        }
        return Flux.just(textResponse(USAGE));
    }

    @Override
    public String getModelName() {
        return "skill-command-mock";
    }

    private static boolean isLoadCommand(String input) {
        return input.regionMatches(
                true, 0, LOAD_COMMAND + " ", 0, LOAD_COMMAND.length() + 1);
    }

    private ChatResponse loadSkill(String name, List<CatalogEntry> catalog) {
        if (name.isEmpty()) {
            return textResponse("Usage: " + LOAD_COMMAND + " <name>\n\n" + renderCatalog(catalog));
        }
        CatalogEntry entry = find(catalog, name);
        if (entry == null) {
            return textResponse("No skill named '" + name + "' reached this agent.\n\n"
                    + renderCatalog(catalog));
        }
        String arguments = "{\"skillId\":\"" + escapeJson(entry.skillId())
                + "\",\"path\":\"" + SKILL_MARKDOWN + "\"}";
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("mock-" + entry.skillId())
                .name(LOAD_SKILL_TOOL)
                .input(Map.of("skillId", entry.skillId(), "path", SKILL_MARKDOWN))
                .content(arguments)
                .build();
        return ChatResponse.builder()
                .content(List.of(toolUse))
                .usage(new ChatUsage(0, 0, 0))
                .finishReason("tool_calls")
                .build();
    }

    private static CatalogEntry find(List<CatalogEntry> catalog, String name) {
        for (CatalogEntry entry : catalog) {
            if (name.equalsIgnoreCase(entry.name()) || name.equalsIgnoreCase(entry.skillId())) {
                return entry;
            }
        }
        return null;
    }

    private static String renderCatalog(List<CatalogEntry> catalog) {
        if (catalog.isEmpty()) {
            return "No skill was injected into the system prompt. Check that Polaris has "
                    + "published skills and that they are mounted on the configured service.";
        }
        StringBuilder text =
                new StringBuilder("Skills pulled from Polaris (" + catalog.size() + "):\n");
        for (CatalogEntry entry : catalog) {
            text.append("- ").append(entry.name());
            if (!entry.description().isEmpty()) {
                text.append(" — ").append(entry.description());
            }
            text.append(" [skill-id=").append(entry.skillId()).append("]\n");
        }
        text.append("\nRun '")
                .append(LOAD_COMMAND)
                .append(" <name>' to print a skill's SKILL.md.");
        return text.toString();
    }

    private static List<CatalogEntry> parseCatalog(String systemText) {
        List<CatalogEntry> entries = new ArrayList<>();
        Matcher section = AVAILABLE_SKILLS.matcher(systemText);
        if (!section.find()) {
            return entries;
        }
        Matcher blocks = SKILL_BLOCK.matcher(section.group(1));
        while (blocks.find()) {
            String block = blocks.group(1);
            String skillId = tagValue(block, "skill-id");
            if (skillId.isEmpty()) {
                continue;
            }
            String name = tagValue(block, "name");
            entries.add(new CatalogEntry(
                    name.isEmpty() ? skillId : name, tagValue(block, "description"), skillId));
        }
        return entries;
    }

    private static String tagValue(String block, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL)
                .matcher(block);
        return matcher.find() ? unescapeXml(matcher.group(1).trim()) : "";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeXml(String value) {
        return value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(0, 0, 0))
                .finishReason("stop")
                .build();
    }

    private static String lastToolOutput(List<Msg> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg == null) {
                continue;
            }
            if (msg.getRole() == MsgRole.USER) {
                return null;
            }
            List<ToolResultBlock> results = msg.getContentBlocks(ToolResultBlock.class);
            if (!results.isEmpty()) {
                return toolResultText(results);
            }
        }
        return null;
    }

    private static String toolResultText(List<ToolResultBlock> results) {
        StringBuilder text = new StringBuilder();
        for (ToolResultBlock result : results) {
            for (ContentBlock block : result.getOutput()) {
                if (block instanceof TextBlock textBlock) {
                    text.append(textBlock.getText());
                }
            }
        }
        return text.toString();
    }

    private static String systemText(List<Msg> messages) {
        if (messages == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Msg msg : messages) {
            if (msg != null && msg.getRole() == MsgRole.SYSTEM) {
                String content = msg.getTextContent();
                if (content != null) {
                    text.append(content).append('\n');
                }
            }
        }
        return text.toString();
    }

    private static String lastUserText(List<Msg> messages) {
        if (messages == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg != null && msg.getRole() == MsgRole.USER) {
                String text = msg.getTextContent();
                return text == null ? "" : text;
            }
        }
        return "";
    }

    private record CatalogEntry(String name, String description, String skillId) {}
}
