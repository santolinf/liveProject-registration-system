package com.manning.liveproject.camel.supplies.model;

import lombok.Getter;
import lombok.ToString;
import org.apache.camel.dataformat.bindy.annotation.CsvRecord;
import org.apache.camel.dataformat.bindy.annotation.DataField;

import java.time.LocalDate;

@Getter
@ToString
@CsvRecord(separator = ",", crlf = "UNIX")
public class SupplyRecord {

    @DataField(pos = 1)
    private String shipmentCode;
    @DataField(pos = 2)
    private String supplierCode;
    @DataField(pos = 3)
    private String itemCode;
    @DataField(pos = 4)
    private Integer quantity;
    @DataField(pos = 5, pattern = "yyyy-MM-dd")
    private LocalDate shipmentDate;
}
