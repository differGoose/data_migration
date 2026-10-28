package com.datamigration.model;

import java.sql.JDBCType;
public record FieldMapping(String sourceColumn, JDBCType targetJdbcType) {}
