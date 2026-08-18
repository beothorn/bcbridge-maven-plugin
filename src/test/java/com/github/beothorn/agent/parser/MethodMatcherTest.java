package com.github.beothorn.agent.parser;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

class MethodMatcherTest {

    static class RegexFixture {
        void testA() {
        }

        void testB() {
        }

        void testC() {
        }
    }

    @Test
    void happyDay() throws CompilationException {
        ElementMatcherFromExpression elementMatcherFromExpression = ElementMatcherFromExpression.forExpression("a.b.c");
        Assertions.assertEquals(0, elementMatcherFromExpression.getClassAndMethodMatchers().size());
        Assertions.assertEquals("name(contains(a.b.c))", elementMatcherFromExpression.getClassMatcher().toString());
    }

    @Test
    void happyDayWithFunction() throws CompilationException {
        ElementMatcherFromExpression elementMatcherFromExpression = ElementMatcherFromExpression.forExpression("a.b.c#f");
        List<ClassAndMethodMatcher> classAndMethodMatchers = elementMatcherFromExpression.getClassAndMethodMatchers();
        Assertions.assertEquals(1, classAndMethodMatchers.size());
        Assertions.assertEquals("name(contains(a.b.c))", elementMatcherFromExpression.getClassMatcher().toString());
        Assertions.assertEquals("name(contains(a.b.c))", classAndMethodMatchers.get(0).classMatcher.toString());
        Assertions.assertEquals("name(contains(f))", classAndMethodMatchers.get(0).methodMatcher.toString());
    }

    @Test
    void happyDayWithTwoMatcherAndOneFunctionMatcher() throws CompilationException {
        ElementMatcherFromExpression elementMatcherFromExpression = ElementMatcherFromExpression.forExpression("a.b.c||(d.e.f#f)");
        List<ClassAndMethodMatcher> classAndMethodMatchers = elementMatcherFromExpression.getClassAndMethodMatchers();
        Assertions.assertEquals(1, classAndMethodMatchers.size());
        Assertions.assertEquals("(name(contains(a.b.c)) or name(contains(d.e.f)))", elementMatcherFromExpression.getClassMatcher().toString());
        Assertions.assertEquals("name(contains(d.e.f))", classAndMethodMatchers.get(0).classMatcher.toString());
        Assertions.assertEquals("name(contains(f))", classAndMethodMatchers.get(0).methodMatcher.toString());
    }

    @Test
    void happyDayWithTwoMatcherAndtwoFunctionMatcher() throws CompilationException {
        ElementMatcherFromExpression elementMatcherFromExpression = ElementMatcherFromExpression.forExpression("(a.b.c#ff)||(d.e.f#f)");
        List<ClassAndMethodMatcher> classAndMethodMatchers = elementMatcherFromExpression.getClassAndMethodMatchers();
        Assertions.assertEquals(2, classAndMethodMatchers.size());
        Assertions.assertEquals("(name(contains(a.b.c)) or name(contains(d.e.f)))", elementMatcherFromExpression.getClassMatcher().toString());
        Assertions.assertEquals("name(contains(a.b.c))", classAndMethodMatchers.get(0).classMatcher.toString());
        Assertions.assertEquals("name(contains(ff))", classAndMethodMatchers.get(0).methodMatcher.toString());
        Assertions.assertEquals("name(contains(d.e.f))", classAndMethodMatchers.get(1).classMatcher.toString());
        Assertions.assertEquals("name(contains(f))", classAndMethodMatchers.get(1).methodMatcher.toString());
    }

    @Test
    void happyDayWithConditionalsMatchers() throws CompilationException {
        ElementMatcherFromExpression elementMatcherFromExpression = ElementMatcherFromExpression.forExpression("((classX||classY)#(funX||funY))||((classW||classZ)#(funW&&funZ))");
        List<ClassAndMethodMatcher> classAndMethodMatchers = elementMatcherFromExpression.getClassAndMethodMatchers();
        Assertions.assertEquals(2, classAndMethodMatchers.size());
        Assertions.assertEquals("(name(contains(classX)) or name(contains(classY)) or name(contains(classW)) or name(contains(classZ)))", elementMatcherFromExpression.getClassMatcher().toString());
        Assertions.assertEquals("(name(contains(classX)) or name(contains(classY)))", classAndMethodMatchers.get(0).classMatcher.toString());
        Assertions.assertEquals("(name(contains(funX)) or name(contains(funY)))", classAndMethodMatchers.get(0).methodMatcher.toString());
        Assertions.assertEquals("(name(contains(classW)) or name(contains(classZ)))", classAndMethodMatchers.get(1).classMatcher.toString());
        Assertions.assertEquals("(name(contains(funW)) and name(contains(funZ)))", classAndMethodMatchers.get(1).methodMatcher.toString());
    }

    @Test
    void nameMatchesSupportsRegexForClassNames() throws CompilationException {
        ElementMatcherFromExpression expression = ElementMatcherFromExpression.forExpression(
                "nameMatches(.*MethodMatcherTest[$]RegexFixtur[e])");

        Assertions.assertTrue(expression.getClassMatcher().matches(TypeDescription.ForLoadedType.of(RegexFixture.class)));
        Assertions.assertFalse(expression.getClassMatcher().matches(TypeDescription.ForLoadedType.of(MethodMatcherTest.class)));
    }

    @Test
    void nameMatchesSupportsRegexForMethodNames() throws Exception {
        ElementMatcherFromExpression expression = ElementMatcherFromExpression.forExpression(
                "nameMatches(.*RegexFixture)#nameMatches(test[AB])");
        ClassAndMethodMatcher matcher = expression.getClassAndMethodMatchers().get(0);

        Assertions.assertTrue(matcher.methodMatcher.matches(method("testA")));
        Assertions.assertTrue(matcher.methodMatcher.matches(method("testB")));
        Assertions.assertFalse(matcher.methodMatcher.matches(method("testC")));
    }

    private static MethodDescription method(String name) throws NoSuchMethodException {
        Method method = RegexFixture.class.getDeclaredMethod(name);
        return new MethodDescription.ForLoadedMethod(method);
    }
}
