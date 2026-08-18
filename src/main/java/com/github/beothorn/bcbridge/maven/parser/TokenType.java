package com.github.beothorn.bcbridge.maven.parser;

public enum TokenType {
    STRING_VALUE,
    FUNCTION_MATCHER_VALUE,
    FUNCTION_CALL,
    OPERATOR_OR,
    OPERATOR_AND,
    OPERATOR_NOT,
    OPEN_PAREN,
    CLOSE_PAREN
}