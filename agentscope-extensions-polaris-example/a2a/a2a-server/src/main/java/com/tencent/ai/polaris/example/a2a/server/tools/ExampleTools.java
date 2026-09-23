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

package com.tencent.ai.polaris.example.a2a.server.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Example tools the hosted agent can call during a conversation.
 *
 * <p>Mirrors the tools in the agentscope A2A documentation example, so the agent can answer weather / arithmetic /
 * time questions via tool calls rather than guessing.
 */
public class ExampleTools {

    private final Random random = new Random();

    @Tool(name = "get_weather", description = "Get current weather information for a city")
    public ToolResultBlock getWeather(
            @ToolParam(name = "city", description = "The city name (e.g., 'Beijing', 'New York')")
                    String city) {
        String[] conditions = {"Sunny", "Cloudy", "Partly Cloudy", "Rainy", "Overcast"};
        String condition = conditions[random.nextInt(conditions.length)];
        int temperature = random.nextInt(35) + 5;
        int humidity = random.nextInt(60) + 30;
        return ToolResultBlock.text(String.format(
                "Weather in %s:%n- Condition: %s%n- Temperature: %d°C%n- Humidity: %d%%",
                city, condition, temperature, humidity));
    }

    @Tool(name = "calculate", description = "Perform a simple arithmetic calculation (supports +, -, *, /)")
    public ToolResultBlock calculate(
            @ToolParam(name = "expression", description = "The arithmetic expression to calculate (e.g., '2 + 3 * 4')")
                    String expression) {
        try {
            return ToolResultBlock.text("Result: " + formatNumber(evaluateExpression(expression)));
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to calculate: " + e.getMessage());
        }
    }

    @Tool(name = "get_current_time", description = "Get the current date and time")
    public ToolResultBlock getCurrentTime() {
        return ToolResultBlock.text("Current time: "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    private double evaluateExpression(String expression) {
        expression = expression.replaceAll("\\s+", "");
        int split = findOperator(expression, '+', '-');
        if (split > 0) {
            char op = expression.charAt(split);
            double left = evaluateExpression(expression.substring(0, split));
            double right = evaluateExpression(expression.substring(split + 1));
            return op == '+' ? left + right : left - right;
        }
        split = findOperator(expression, '*', '/');
        if (split > 0) {
            char op = expression.charAt(split);
            double left = evaluateExpression(expression.substring(0, split));
            double right = evaluateExpression(expression.substring(split + 1));
            return op == '*' ? left * right : left / right;
        }
        if (expression.startsWith("(") && expression.endsWith(")")) {
            return evaluateExpression(expression.substring(1, expression.length() - 1));
        }
        try {
            return Double.parseDouble(expression);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value: '" + expression + "'");
        }
    }

    private int findOperator(String expression, char op1, char op2) {
        int depth = 0;
        for (int i = expression.length() - 1; i >= 0; i--) {
            char c = expression.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') depth--;
            else if (depth == 0 && (c == op1 || c == op2) && i > 0) {
                return i;
            }
        }
        return -1;
    }

    private String formatNumber(double value) {
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        return String.format("%.4f", value);
    }
}
