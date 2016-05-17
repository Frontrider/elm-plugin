package org.elmlang.intellijplugin.features.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.lang.ASTNode;
import com.intellij.lang.parser.GeneratedParserUtilBase;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.Consumer;
import com.intellij.util.ProcessingContext;
import org.elmlang.intellijplugin.ElmModuleIndex;
import org.elmlang.intellijplugin.psi.*;
import org.elmlang.intellijplugin.psi.scope.ElmScope;
import org.elmlang.intellijplugin.utils.TypeFilter;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class ElmCompletionProvider extends CompletionProvider<CompletionParameters> {
    private static final String[] KEYWORDS = new String[] {
            "module",
            "where",
            "import",
            "as",
            "exposing",
            "type",
            "alias",
            "case",
            "of",
            "let",
            "in",
            "infix",
            "infixl",
            "infixr",
            "if",
            "then",
            "else"
    };

    @Override
    protected void addCompletions(@NotNull CompletionParameters parameters, ProcessingContext context, @NotNull CompletionResultSet resultSet) {
        PsiElement position = parameters.getPosition();
        PsiElement parent = position.getParent();
        PsiElement grandParent = Optional.ofNullable(parent)
                .map(PsiElement::getParent)
                .orElse(null);
        if (parent instanceof ElmFile || parent instanceof GeneratedParserUtilBase.DummyBlock) {
            addCompletionsInInvalidExpression(position, resultSet);
        } else if (grandParent instanceof ElmLowerCasePath
                && parent.getStartOffsetInParent() == 0) {
            addValueCompletions(
                    ElmScope.scopeFor((ElmLowerCaseId) parent),
                    resultSet
            );
        } else if (grandParent instanceof ElmMixedCasePath
                || grandParent instanceof ElmUpperCasePath) {
            addTypeOrModuleCompletions(parent, resultSet);
        }
    }

    private static void addTypeOrModuleCompletions(PsiElement element, CompletionResultSet resultSet) {
        ElmFile file = (ElmFile) element.getContainingFile();
        if (element.getStartOffsetInParent() == 0) {
            addSingleUpperCaseIdCompletions(file, resultSet);
        } else if (Optional.ofNullable(element.getPrevSibling())
                .flatMap(e -> Optional.ofNullable(e.getPrevSibling()))
                .filter(e -> e instanceof ElmUpperCaseId)
                .isPresent()) {
            addModuleCompletions(file, element, resultSet);
        }
    }

    private static void addModuleCompletions(ElmFile file, PsiElement element, CompletionResultSet resultSet) {
        List<PsiElement> module = Arrays.stream(element.getParent().getChildren())
                .filter(e -> e instanceof ElmUpperCaseId && e.getStartOffsetInParent() < element.getStartOffsetInParent())
                .collect(Collectors.toList());
        addModuleCompletions(file, module, resultSet);
    }

    private static void addModuleCompletions(ElmFile file, List<PsiElement> modulePrefix, CompletionResultSet resultSet) {
        String moduleNamePrefix = ElmTreeUtil.joinUsingDot(modulePrefix);
        Project project = file.getProject();
        ElmModuleIndex.getAllModuleNames(project).stream()
                .filter(m -> m.startsWith(moduleNamePrefix))
                .forEach(m -> addCompletionsForModule(m, moduleNamePrefix, project, resultSet));
    }

    private static void addCompletionsForModule(String moduleName, String modulePrefix, Project project, CompletionResultSet resultSet) {
        if (moduleName.equals(modulePrefix)) {
            addCompletionsForModule(moduleName, project, resultSet);
        }
    }

    private static void addCompletionsForModule(String moduleName, Project project, CompletionResultSet resultSet) {
        ElmModuleIndex.getFilesByModuleName(moduleName, project).stream()
                .forEach(f -> addCompletionsForFile(f, resultSet));
    }

    private static void addCompletionsForFile(ElmFile file, CompletionResultSet resultSet) {
        Stream.concat(
                file.getExposedValues()
                        .map(e -> (PsiElement)e),
                file.getExposedTypes(TypeFilter.always(true))
                        .map(e -> (PsiElement)e)
        ).forEach(e -> addPsiElementToResult(e, resultSet));
    }

    private void addCompletionsInInvalidExpression(@NotNull PsiElement position, @NotNull CompletionResultSet resultSet) {
        PsiElement prevSibling = position.getPrevSibling();
        if (prevSibling instanceof PsiWhiteSpace) {
            addCompletionsAfterWhiteSpace(position, resultSet);
        } else if (prevSibling instanceof ASTNode
                && ((ASTNode) prevSibling).getElementType().equals(ElmTypes.DOT)) {
            addCompletionsAfterDot(prevSibling, resultSet);
        }
    }

    private static void addCompletionsAfterDot(PsiElement dot, @NotNull CompletionResultSet resultSet) {
        List<PsiElement> upperCaseIds = new LinkedList<>();
        PsiElement elem = dot.getPrevSibling();
        while (elem instanceof ASTNode) {
            IElementType type = ((ASTNode) elem).getElementType();
            if (type.equals(ElmTypes.UPPER_CASE_IDENTIFIER)) {
                upperCaseIds.add(0, elem);
            } else if (!type.equals(ElmTypes.DOT)) {
                break;
            }
            elem = elem.getPrevSibling();
        }
        if (upperCaseIds.size() > 0) {
            addCompletionsForModule(
                    ElmTreeUtil.joinUsingDot(upperCaseIds),
                    dot.getProject(),
                    resultSet
            );
        }
    }

    private static void addCompletionsAfterWhiteSpace(PsiElement position, CompletionResultSet resultSet) {
        char firstChar = position.getText().charAt(0);
        ElmFile file = (ElmFile) position.getContainingFile();
        if (Character.isLowerCase(firstChar)) {
            addValueCompletions(ElmScope.scopeFor(file), resultSet);
        } else if (Character.isUpperCase(firstChar)) {
            addSingleUpperCaseIdCompletions(file, resultSet);
        }
    }

    private static void addValueCompletions(Stream<Optional<ElmLowerCaseId>> stream, CompletionResultSet resultSet) {
        forEachUntilPresent(
                stream,
                id -> addPsiElementToResult(id, resultSet)
        );
        addKeyWords(resultSet);
    }

    private static void addSingleUpperCaseIdCompletions(ElmFile file, CompletionResultSet resultSet) {
        forEachUntilPresent(
                ElmScope.typesFor(file),
                id -> addPsiElementToResult(id, resultSet)
        );
        ElmModuleIndex.getAllModuleNames(file.getProject()).stream()
            .map(ElmCompletionProvider::getModulesFirstPart)
            .forEach(s -> addStringToResult(s, resultSet));
    }

    private static String getModulesFirstPart(String moduleName) {
        int i = moduleName.indexOf('.');
        if (i >= 0) {
            return moduleName.substring(0, i);
        } else {
            return moduleName;
        }
    }

    private static <T> void forEachUntilPresent(Stream<Optional<T>> stream, Consumer<T> consumer) {
        stream.peek(e -> e.ifPresent(consumer::consume))
                .filter(e -> !e.isPresent())
                .findFirst();
    }

    private static void addKeyWords(CompletionResultSet resultSet) {
        Arrays.stream(KEYWORDS)
                .forEach(s -> addStringToResult(s, resultSet));
    }

    private static void addPsiElementToResult(PsiElement element, CompletionResultSet resultSet) {
        addStringToResult(element.getText(), resultSet);
    }

    private static void addStringToResult(String string, CompletionResultSet resultSet) {
        resultSet.addElement(LookupElementBuilder.create(string));
    }
}
