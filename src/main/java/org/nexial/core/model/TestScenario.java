/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.core.model;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.nexial.commons.InvalidInputRuntimeException;
import org.nexial.commons.utils.CollectionUtil;
import org.nexial.commons.utils.TextUtils;
import org.nexial.core.excel.Excel;
import org.nexial.core.excel.Excel.Worksheet;
import org.nexial.core.excel.ExcelAddress;
import org.nexial.core.excel.ExcelArea;
import org.nexial.core.logs.ExecutionLogger;
import org.nexial.core.utils.ConsoleUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.nexial.core.CommandConst.CMD_REPEAT_UNTIL;
import static org.nexial.core.CommandConst.CMD_SECTION;
import static org.nexial.core.NexialConst.Data.END_SCRIPT_IMMEDIATE;
import static org.nexial.core.NexialConst.LogMessage.EXECUTING_TEST_SCENARIO;
import static org.nexial.core.NexialConst.RB;
import static org.nexial.core.NexialConst.OPT_INPUT_EXCEL_FILE;
import static org.nexial.core.excel.ExcelConfig.*;
import static org.nexial.core.model.ExecutionSummary.ExecutionLevel.SCENARIO;

public class TestScenario {
    private final ExecutionContext context;
    private final String name;
    private Worksheet worksheet;
    private TestScenarioMeta meta;
    private final ExecutionSummary executionSummary = new ExecutionSummary();

    private List<TestCase> testCases;
    private Map<String, TestCase> testCaseMap;
    private List<TestStep> allSteps;
    private Map<Integer, TestStep> testStepsByRow;

    public TestScenario(ExecutionContext context, Worksheet worksheet) {
        assert context != null && StringUtils.isNotBlank(context.getId());
        assert worksheet != null && worksheet.getSheet() != null;

        this.context = context;
        this.worksheet = worksheet;
        this.name = worksheet.getName();

        parse();
    }

    public String getName() { return name; }

    public TestScenarioMeta getMeta() { return meta; }

    public ExecutionSummary getExecutionSummary() { return executionSummary; }

    public Worksheet getWorksheet() { return worksheet; }

    public ExecutionContext getContext() { return context; }

    public List<TestCase> getTestCases() { return testCases; }

    public Map<String, TestCase> getTestCaseMap() { return testCaseMap; }

    public List<TestStep> getAllSteps() { return allSteps; }

    public TestCase getTestCase(String name) { return testCaseMap.get(name); }

    public boolean execute() throws IOException {
        ExecutionLogger logger = context.getLogger();
        logger.log(this, EXECUTING_TEST_SCENARIO);

        // by default, only fail fast if we are not in interactive mode
        boolean shouldFailFast = context.isFailFast();
        boolean skipDueToFailFast = false;
        boolean skipDueToEndFast = false;
        boolean skipDueToEndLoop = false;
        boolean allPass = true;

        executionSummary.setName(context.replaceTokens(name));
        executionSummary.setExecutionLevel(SCENARIO);
        executionSummary.setTestScript(worksheet.excel().getOriginalFile());
        executionSummary.setStartTime(System.currentTimeMillis());
        executionSummary.setTotalSteps(CollectionUtils.size(allSteps));

        context.setCurrentScenario(this);
        ExecutionEventListener executionEventListener = context.getExecutionEventListener();
        executionEventListener.onScenarioStart();

        for (TestCase testCase : testCases) {
            context.setCurrentActivity(testCase);

            if (BooleanUtils.toBoolean(System.getProperty(END_SCRIPT_IMMEDIATE, "false"))) {
                logger.log(testCase, RB.Abort.text("activity.endIf"));
                break;
            }

            if (skipDueToFailFast) {
                logger.log(this, RB.Abort.text("activity.failFast"));
                continue;
            }

            if (skipDueToEndFast) {
                logger.log(this, RB.Abort.text("activity.failEnd"));
                continue;
            }

            if (skipDueToEndLoop) {
                logger.log(this, RB.Abort.text("activity.failLoop"));
                continue;
            }

            if (!testCase.execute()) {
                allPass = false;
                if (shouldFailFast || context.isFailImmediate()) {
                    skipDueToFailFast = true;
                } else if (context.isEndImmediate()) {
                    skipDueToEndFast = true;
                } else if (context.isBreakCurrentIteration()) {
                    skipDueToEndLoop = true;
                }
            } else if (context.isEndImmediate()) {
                skipDueToEndFast = true;
                logger.log(testCase, RB.Abort.text("activity.endIf"));
            } else if (context.isBreakCurrentIteration()) {
                skipDueToEndLoop = true;
                logger.log(testCase, RB.Abort.text("activity.endLoopIf"));
            }

            executionSummary.addNestSummary(testCase.getExecutionSummary());
        }

        context.setCurrentActivity(null);

        executionSummary.setEndTime(System.currentTimeMillis());
        executionSummary.setFailedFast(shouldFailFast);
        executionSummary.aggregatedNestedExecutions(context);

        // refill macro steps
        ExecutionResultHelper helper = new ExecutionResultHelper(allSteps, worksheet, executionSummary);
        helper.updateScenarioResults();

        executionEventListener.onScenarioComplete(executionSummary);

        // clear off any scenario-ref incurred during the execution of this scenario.. we don't want current
        // scenario-ref's to taint subsequent scenario
        context.clearScenarioRefData();

        return allPass;
    }

    public TestStep getTestStepByRowIndex(int rowIndex) { return testStepsByRow.get(rowIndex); }

    public List<TestStep> getTestStepsByRowRange(int startRow, int endRow) {
        List<TestStep> testSteps = new ArrayList<>();
        for (int i = startRow; i <= endRow; i++) {
            TestStep testStep = getTestStepByRowIndex(i);
            if (testStep != null) { testSteps.add(testStep); }
        }
        return testSteps;
    }

    public void close() {
        if (CollectionUtils.isNotEmpty(testCases)) {
            testCases.forEach(TestCase::close);
            testCases.clear();
            testCases = null;
        }

        if (worksheet != null) {
            XSSFSheet sheet = worksheet.getSheet();
            if (sheet != null) {
                XSSFWorkbook workbook = sheet.getWorkbook();
                if (workbook != null) {
                    try {
                        workbook.close();
                    } catch (IOException e) {
                        ConsoleUtils.error("Unable to close scenario (" + name + "): " + e.getMessage());
                    }
                }
            }
            worksheet = null;
        }

        if (MapUtils.isNotEmpty(testCaseMap)) {
            testCaseMap.clear();
            testCaseMap = null;
        }

        if (CollectionUtils.isNotEmpty(allSteps)) {
            allSteps.clear();
            allSteps = null;
        }

        if (MapUtils.isNotEmpty(testStepsByRow)) {
            testStepsByRow.clear();
            testStepsByRow = null;
        }
    }

    protected void parse() {
        // 1. parse meta
        meta = TestScenarioMeta.newInstance(worksheet);

        // 2. find last command
        int lastCommandRow = worksheet.findLastDataRow(ADDR_COMMAND_START);

        // the section with the corresponding worksheet that has test steps
        ExcelArea area = new ExcelArea(worksheet,
                                       new ExcelAddress(FIRST_STEP_ROW + ":" + COL_REASON + lastCommandRow),
                                       false);
        testCases = new ArrayList<>();
        testCaseMap = new HashMap<>();
        allSteps = new ArrayList<>();
        testStepsByRow = new HashMap<>();

        String scenarioRef = "Error found in [" + worksheet.getFile().getName() + "][" + name + "]";

        // 3. parse for test steps->test case grouping
        TestCase currentActivity = null;
        for (int i = 0; i < area.getWholeArea().size(); i++) {
            List<XSSFCell> row = area.getWholeArea().get(i);

            XSSFCell cellActivity = row.get(COL_IDX_TESTCASE);
            String activity = Excel.getCellValue(cellActivity);

            validateActivity(activity,
                             CollectionUtil.toList(testCaseMap.keySet()),
                             scenarioRef + "[" + cellActivity.getReference() + "]:");
            if (StringUtils.isNotBlank(activity)) {
                currentActivity = new TestCase();
                currentActivity.setName(TextUtils.toOneLine(activity, true));
                currentActivity.setTestScenario(this);
                testCases.add(currentActivity);
                testCaseMap.put(currentActivity.getName(), currentActivity);
            }

            if (currentActivity != null) {
                TestStep testStep = new TestStep(currentActivity, row, worksheet);
                if (testStep.isCommandRepeater()) { i += collectRepeatingCommandSet(testStep, area, i + 1); }
                currentActivity.addTestStep(testStep);
                allSteps.add(testStep);
                testStepsByRow.put(row.get(0).getRowIndex() + 1, testStep);
            }
        }
    }

    public static void validateActivity(String activity, List<String> existingActivities, String errorPrefix) {
        // detect space only activity name
        if (StringUtils.isNotEmpty(activity) && StringUtils.isAllBlank(activity)) {
            throw new RuntimeException(RB.Fatal.text("activity.bad", errorPrefix));
        }

        // detect leading/trailing non-printable characters
        if (!StringUtils.equals(activity, StringUtils.trim(activity))) {
            throw new RuntimeException(RB.Fatal.text("problematicName", errorPrefix, "activity", activity));
        }

        boolean activityFound = CollectionUtils.isNotEmpty(existingActivities);
        boolean hasActivity = StringUtils.isNotBlank(activity);
        if (!activityFound && !hasActivity) {
            // first row must define test case (hence at least 1 test case is required)
            throw new RuntimeException(RB.Fatal.text("activity.missing", errorPrefix));
        }

        if (hasActivity && existingActivities.contains(activity)) {
            // found duplicate activity name!
            String error = RB.Fatal.text("activity.dup", errorPrefix, activity);
            String currentExcelScript = System.getProperty(OPT_INPUT_EXCEL_FILE);
            if (StringUtils.isNotBlank(currentExcelScript)) {
                throw new InvalidInputRuntimeException(error, currentExcelScript);
            } else {
                throw new RuntimeException(error);
            }
        }
    }

    protected int collectRepeatingCommandSet(TestStep testStep, ExcelArea area, int startFrom) {
        List<List<XSSFCell>> wholeArea = area.getWholeArea();
        if (testStep == null || !testStep.isCommandRepeater() ||
            CollectionUtils.isEmpty(wholeArea) || startFrom < 1) {
            return 0;
        }

        // expectation: first parameter is the number of test steps for the repeats
        // expectation: second parameter is the max. wait time in ms
        String errMsg = "[ROW " + (startFrom + ADDR_COMMAND_START.getRowStartIndex()) + "] from worksheet "
                        + testStep.getWorksheet().getName() +
                        " wrong parameters specified for " + CMD_REPEAT_UNTIL + ": " + testStep.getParams();
        if (CollectionUtils.size(testStep.getParams()) != 2) {
            ConsoleUtils.error(errMsg);
            throw new RuntimeException(errMsg);
        }

        String steps = context.replaceTokens(testStep.getParams().get(0));
        int numOfStepsIncluded = NumberUtils.toInt(steps);
        if (numOfStepsIncluded < 1) {
            ConsoleUtils.error(errMsg);
            throw new RuntimeException(errMsg);
        }

        if ((startFrom + numOfStepsIncluded) > wholeArea.size()) {
            String errMsg1 = errMsg + " - number of steps specified greater than available in this test scenario";
            ConsoleUtils.error(errMsg1);
            throw new RuntimeException(errMsg1);
        }

        String maxWaitMs = context.replaceTokens(testStep.getParams().get(1));
        long maxWait = NumberUtils.toLong(maxWaitMs);
        if (maxWait != -1 && maxWait < 1000) {
            String errMsg1 = errMsg + " - minimum wait time is 1000ms: " + maxWait;
            ConsoleUtils.error(errMsg1);
            throw new RuntimeException(errMsg1);
        }

        CommandRepeater commandRepeater = new CommandRepeater(testStep, maxWait);
        TestCase currentTestCase = testStep.getTestCase();
        for (int i = startFrom; i < (startFrom + numOfStepsIncluded); i++) {
            List<XSSFCell> row = wholeArea.get(i);
            TestStep nextStep = new TestStep(currentTestCase, row, testStep.getWorksheet());
            if (nextStep.isMacroExpander()) { nextStep.macroPartOfRepeatUntil = true; }

                /*// To nested repeatuntil command
                if(nextStep.isCommandRepeater()) {
                    i += collectRepeatingCommandSet(nextStep, area, nextStep.rowIndex + 1);
                }*/

            // check that first step is an assertion - REQUIRED
            if (i == startFrom && !StringUtils.startsWith(nextStep.getCommand(), "assert")) {
                String errMsg1 = "[ROW " + (startFrom + area.getTopLeft().getRowIndex()) + "] " +
                                 "wrong command for the first step.  First command MUST be an assertion " +
                                 "(i.e., an assert*() command), not " + nextStep.getCommandFQN();
                ConsoleUtils.error(errMsg1);
                throw new RuntimeException(errMsg1);
            }

            commandRepeater.addStep(nextStep);

            // to collect nested section commands
            int num = collectSectionCommands(nextStep, i, commandRepeater, wholeArea, currentTestCase);
            numOfStepsIncluded += num;
            i += num;
        }

        testStep.setCommandRepeater(commandRepeater);
        return (numOfStepsIncluded);
    }

    private int collectSectionCommands(TestStep testStep, int startFrom,
                                       CommandRepeater commandRepeater,
                                       List<List<XSSFCell>> wholeArea,
                                       TestCase currentTestCase) {

        if (!StringUtils.equals(testStep.getCommandFQN(), CMD_SECTION)) { return 0; }
        String errMsg = "[ROW " + (startFrom + ADDR_COMMAND_START.getRowStartIndex()) + "] from worksheet "
                        + testStep.getWorksheet().getName() +
                        " wrong parameters specified for " + CMD_SECTION + ": " + testStep.getParams();
        String steps = context.replaceTokens(testStep.getParams().get(0));
        int numOfStepsIncluded = NumberUtils.toInt(steps);
        if (numOfStepsIncluded < 1) {
            ConsoleUtils.error(errMsg);
            throw new RuntimeException(errMsg);
        }

        if ((startFrom + numOfStepsIncluded) > wholeArea.size()) {
            String errMsg1 = errMsg + " - number of steps specified greater than available in this test scenario";
            ConsoleUtils.error(errMsg1);
            throw new RuntimeException(errMsg1);
        }

        for (int i = startFrom + 1; i <= (startFrom + numOfStepsIncluded); i++) {
            List<XSSFCell> row = wholeArea.get(i);
            TestStep nextStep = new TestStep(currentTestCase, row, testStep.getWorksheet());
            if (nextStep.isMacroExpander()) { nextStep.macroPartOfRepeatUntil = true; }
            commandRepeater.addStep(nextStep);
            if (StringUtils.equals(nextStep.getCommandFQN(), CMD_SECTION)) {
                int stepCount = collectSectionCommands(nextStep, i, commandRepeater, wholeArea, currentTestCase);
                numOfStepsIncluded += stepCount;
                i += stepCount;
            }
        }
        return numOfStepsIncluded;
    }

}
