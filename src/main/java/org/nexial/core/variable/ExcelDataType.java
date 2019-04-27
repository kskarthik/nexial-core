/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.core.variable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.nexial.commons.utils.FileUtil;
import org.nexial.commons.utils.TextUtils;
import org.nexial.core.excel.Excel;
import org.nexial.core.excel.Excel.Worksheet;
import org.nexial.core.excel.ExcelAddress;

import static java.lang.System.lineSeparator;
import static org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK;

public class ExcelDataType extends ExpressionDataType<Excel> {
    private ExcelTransformer transformer = new ExcelTransformer();
    private String filePath;
    private List<String> worksheetNames;
    private List<List<String>> capturedValues;
    private Worksheet currentSheet;
    // private String currentSheetName;
    private ExcelAddress currentRange;

    public ExcelDataType(String textValue) throws TypeConversionException { super(textValue); }

    private ExcelDataType() { super(); }

    public List<String> getWorksheetNames() { return worksheetNames; }

    @Override
    public String getName() { return "EXCEL"; }

    @Override
    public String toString() { return getName() + "(" + lineSeparator() + getTextValue() + lineSeparator() + ")"; }

    public String getFilePath() { return filePath; }

    public List<List<String>> getCapturedValues() { return capturedValues; }

    public void setCapturedValues(List<List<String>> capturedValues) {
        this.capturedValues = capturedValues;
        textValue = "";
        if (!CollectionUtils.isNotEmpty(capturedValues)) { return; }

        char delim = ',';
        String recordDelim = "\r\n";
        capturedValues.forEach(row -> textValue += TextUtils.toString(row, delim + "") + recordDelim);
        textValue = StringUtils.removeEnd(textValue, recordDelim);
        if (StringUtils.isBlank(textValue)) { textValue = ""; }
    }

    public Worksheet getCurrentSheet() { return currentSheet; }

    public void setCurrentSheet(Worksheet currentSheet) { this.currentSheet = currentSheet; }

    // public String getCurrentSheetName() { return currentSheetName; }
    //
    // public void setCurrentSheetName(String currentSheetName) { this.currentSheetName = currentSheetName; }

    public ExcelAddress getCurrentRange() { return currentRange; }

    public void setCurrentRange(ExcelAddress currentRange) { this.currentRange = currentRange; }

    // @Override
    // public Excel getValue() {
    //     if (StringUtils.isBlank(filePath)) {
    //         throw new IllegalArgumentException("No valid file specified for current EXCEL expression");
    //     }
    //
    //     try {
    //         return new Excel(new File(filePath), false, false);
    //     } catch (IOException e) {
    //         throw new IllegalArgumentException("Error opening " + filePath + ": " + e.getMessage(), e);
    //     }
    // }

    @Override
    Transformer getTransformer() { return transformer; }

    @Override
    ExcelDataType snapshot() {
        ExcelDataType snapshot = new ExcelDataType();
        snapshot.transformer = transformer;
        snapshot.value = value;
        snapshot.textValue = textValue;
        snapshot.filePath = filePath;
        snapshot.worksheetNames = worksheetNames;
        snapshot.capturedValues = new ArrayList<>(capturedValues);
        snapshot.currentSheet = currentSheet;
        snapshot.currentRange = currentRange;
        return snapshot;
    }

    @Override
    protected void init() throws TypeConversionException {
        if (StringUtils.isBlank(textValue)) {
            throw new TypeConversionException(getName(), textValue, "Not a valid spreadsheet: '" + textValue + "'");
        }

        try {
            if (!FileUtil.isFileReadable(textValue)) {
                value = Excel.newExcel(new File(textValue));
            } else {
                value = new Excel(new File(textValue), false, false);
            }

            filePath = textValue;
            List<Worksheet> worksheets = value.getWorksheetsStartWith("");
            if (CollectionUtils.isNotEmpty(worksheets)) {
                worksheetNames = new ArrayList<>();
                worksheets.forEach(worksheet -> worksheetNames.add(worksheet.getName()));
            }

            // Excel excel;
            // if (!FileUtil.isFileReadable(textValue)) {
            //     excel = Excel.newExcel(new File(textValue));
            // } else {
            //     excel = new Excel(new File(textValue), false, false);
            // }
            //
            // filePath = textValue;
            // List<Worksheet> worksheets = excel.getWorksheetsStartWith("");
            // if (CollectionUtils.isNotEmpty(worksheets)) {
            //     worksheetNames = new ArrayList<>();
            //     worksheets.forEach(worksheet -> worksheetNames.add(worksheet.getName()));
            // }
            //
            // excel.close();
        } catch (IOException e) {
            throw new TypeConversionException(getName(),
                                              textValue,
                                              "Error opening " + textValue + ": " + e.getMessage(),
                                              e);
        }
    }

    protected void read(Worksheet worksheet, ExcelAddress range) {
        worksheet.getSheet().getWorkbook().setMissingCellPolicy(CREATE_NULL_AS_BLANK);
        currentRange = range;
        currentSheet = worksheet;
        setCapturedValues(worksheet.readRange(range));
    }

    // protected void read(String sheet, String range) throws IOException { read(sheet, new ExcelAddress(range)); }
    //
    // protected void read(String sheet, ExcelAddress range) throws IOException {
    //     Excel excel = getValue();
    //     excel.getWorkbook().setMissingCellPolicy(CREATE_NULL_AS_BLANK);
    //
    //     Worksheet worksheet = excel.worksheet(sheet, true);
    //     currentRange = range;
    //     currentSheetName = sheet;
    //     setCapturedValues(worksheet.readRange(currentRange));
    //
    //     excel.close();
    // }
    //
    // protected void clearCells(String range) throws IOException {
    //     Excel excel = getValue();
    //     excel.getWorkbook().setMissingCellPolicy(CREATE_NULL_AS_BLANK);
    //     excel.worksheet(currentSheetName, true).clearCells(new ExcelAddress(range));
    //     excel.save();
    //     excel.close();
    // }
    //
    // protected void writeAcross(String startAddr, List<List<String>> rows) throws IOException {
    //     Excel excel = getValue();
    //     excel.getWorkbook().setMissingCellPolicy(CREATE_NULL_AS_BLANK);
    //     excel.worksheet(currentSheetName).writeAcross(new ExcelAddress(StringUtils.trim(startAddr)), rows);
    //     excel.save();
    //     excel.close();
    // }
    //
    // protected void writeDown(String startAddr, List<List<String>> columns) throws IOException {
    //     Excel excel = getValue();
    //     excel.getWorkbook().setMissingCellPolicy(CREATE_NULL_AS_BLANK);
    //     excel.worksheet(currentSheetName).writeDown(new ExcelAddress(StringUtils.trim(startAddr)), columns);
    //     excel.save();
    //     excel.close();
    // }
}