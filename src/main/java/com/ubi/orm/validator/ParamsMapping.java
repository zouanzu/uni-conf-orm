package com.ubi.orm.validator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.*;

// 字段规则类：封装字段名、类型及关联的校验器
@Data
// 单个字段的校验规则（对应JSON中的一个对象）
@JsonIgnoreProperties(ignoreUnknown = true) // 忽略JSON中未定义的字段
public class ParamsMapping {
    private String field;
    private String alias;
    private String source = "all";
    private String dataType = "string";
    private List<Validator> validators ;
}
