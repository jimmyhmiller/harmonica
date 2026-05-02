package com.jimmyhmiller.harmonica.ast;

public sealed interface Pattern extends Node permits Identifier, ObjectPattern, ArrayPattern, RestElement, AssignmentPattern, MemberExpression {
}
