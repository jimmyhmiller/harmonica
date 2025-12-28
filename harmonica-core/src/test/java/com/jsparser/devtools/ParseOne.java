package com.jsparser.devtools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jsparser.Parser;
import com.jsparser.TestObjectMapper;
import com.jsparser.Test262Utils;
import com.jsparser.TestObjectMapper;
import com.jsparser.ast.Program;
import com.jsparser.TestObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;

public class ParseOne {
    public static void main(String[] args) throws Exception {
        String jsFile = args[0];
        String source = Files.readString(Paths.get(jsFile));
        
        boolean isModule = Test262Utils.hasModuleFlag(source) || jsFile.endsWith("_FIXTURE.js");
        Program program = Parser.parse(source, isModule);
        
        ObjectMapper mapper = TestObjectMapper.get();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        String json = mapper.writeValueAsString(program);
        System.out.println(json);
    }
}
