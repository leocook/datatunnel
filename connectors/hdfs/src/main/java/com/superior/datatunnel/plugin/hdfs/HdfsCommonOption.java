package com.superior.datatunnel.plugin.hdfs;

import com.superior.datatunnel.api.model.BaseCommonOption;
import com.superior.datatunnel.api.model.DataTunnelSinkOption;
import com.superior.datatunnel.api.model.DataTunnelSourceOption;
import com.superior.datatunnel.common.enums.FileFormat;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

@Data
public class HdfsCommonOption extends BaseCommonOption
        implements DataTunnelSourceOption, DataTunnelSinkOption {

    @NotNull(message = "format can not null")
    private FileFormat format;

    @NotBlank(message = "filePath can not blank")
    private String filePath;

    @NotEmpty(message = "columns can not empty")
    private String[] columns = new String[]{"*"};

    @Override
    public String[] getColumns() {
        return columns;
    }
}
