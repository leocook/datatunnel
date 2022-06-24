package com.superior.datatunnel.hive;

import com.superior.datatunnel.api.model.SinkOption;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;

@Data
public class HiveSinkOption extends SinkOption {

    @NotBlank(message = "databaseName can not blank")
    private String databaseName;

    @NotBlank(message = "tableName can not blank")
    private String tableName;

    @NotEmpty(message = "columns can not empty")
    private String[] columns;

    private String partition;

    private String writeMode = "append";
}
