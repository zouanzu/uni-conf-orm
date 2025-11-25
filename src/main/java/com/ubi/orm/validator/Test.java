package com.ubi.orm.validator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Test {
    public static void main(String[] args) throws Exception {
        // 示例1：通过JSON字符串定义paramsMapping规则
        String paramsMappingJson =
                "    {\n" +
                "        \"field\": \"id\",\n" +
                "        \"source\": \"body\",\n" +
                "        \"validators\": [\n" +
                "            {\"type\": \"number\", \"message\": \"ID必须为数字\"},\n" +
                "            {\"type\": \"min\", \"param\": 1, \"message\": \"ID不能小于1\"}\n" +
                "        ]\n" +
                "    }\n";

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            // 将JSON数组解析为FieldRule列表（需引入Jackson依赖）
            ParamsMapping paramMapping = objectMapper.readValue(paramsMappingJson, new TypeReference<ParamsMapping>() {});
            System.out.println("paramMapping："+paramMapping.toString());
            // 创建校验器
            Joi validator = new Joi(paramsMappingJson);

            // 待校验的数据源（模拟HTTP请求中的body、params等）
            Map<String, Map<String, Object>> dataSource = new HashMap<>();
            // 模拟body数据
            Map<String, Object> bodyData = new HashMap<>();
            bodyData.put("id", "0");       // 违反min(1)
            bodyData.put("username", "a"); // 违反minlen(2)
            dataSource.put("body", bodyData);

            // 执行校验
            validator.validate(4);

            // 输出校验结果（key为alias，如username的alias是keyword）
            System.out.println("校验错误：");
            // 输出：校验错误：{id=[ID不能小于1], keyword=[用户名最短2位]}


            // 示例2：直接通过Java对象定义paramsMapping规则（非JSON场景）
            List<ParamsMapping> rules = new ArrayList<>();
            // 定义age字段规则
            ParamsMapping ageRule = new ParamsMapping();
            ageRule.setField("age");
            ageRule.setSource("params");
            List<Validator> ageValidators = new ArrayList<>();
            Validator ageNum = new Validator();
            ageNum.setType("number");
            ageValidators.add(ageNum);
            Validator ageMax = new Validator();
            ageMax.setType("max");
            ageMax.setParam(150);
            ageValidators.add(ageMax);
            ageRule.setValidators(ageValidators);
            rules.add(ageRule);

            // 创建校验器并验证
            Joi validator2 = new Joi(ageRule);
            Map<String, Object> paramsData = new HashMap<>();
            paramsData.put("age", "120"); // 违反max(150)
            Map<String, Map<String, Object>> dataSource2 = new HashMap<>();
            dataSource2.put("params", paramsData);
            System.out.println("age校验错误：" + validator2.validate("120"));
            // 输出：age校验错误：{age=[age不能大于150]}
        } catch (Exception e){
            System.out.println(e.getMessage());
            e.printStackTrace();
        }

    }
}
