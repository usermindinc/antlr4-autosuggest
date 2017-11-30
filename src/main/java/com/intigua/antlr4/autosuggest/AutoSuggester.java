package com.intigua.antlr4.autosuggest;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.SetTransition;
import org.antlr.v4.runtime.atn.Transition;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Suggests completions for given text, using a given ANTLR4 grammar.
 */
public class AutoSuggester {
    private static final Logger logger = LoggerFactory.getLogger(AutoSuggester.class);

    private final LexerAndParserFactory lexerAndParserFactory;
    private final String input;
    private final Set<String> collectedSuggestions = new HashSet<String>();

    private List<? extends Token> inputTokens;
    private String untokenizedText = "";
    private ATN parserAtn;
    private String indent = "";

    public AutoSuggester(LexerAndParserFactory lexerAndParserFactory, String input) {
        this.lexerAndParserFactory = lexerAndParserFactory;
        this.input = input;
    }

    public Collection<String> suggestCompletions() {
        tokenizeInput();
        createParserAtn();
        runParserAtnAndCollectSuggestions();
        return collectedSuggestions;
    }

    private void tokenizeInput() {
        Lexer lexer = createLexerWithUntokenizedTextDetection();
        this.inputTokens = lexer.getAllTokens(); // side effect: also fills this.untokenizedText
        logger.debug("TOKENS FOUND IN FIRST PASS:");
        for (Token token : inputTokens) {
            logger.debug(token.toString());
        }
    }

    private void createParserAtn() {
        Parser parser = lexerAndParserFactory.createParser(new CommonTokenStream(createLexer()));
        logger.debug("Parser rule names: " + StringUtils.join(parser.getRuleNames(), ", "));
        parserAtn = parser.getATN();
    }

    private void runParserAtnAndCollectSuggestions() {
        ATNState initialState = parserAtn.states.get(0);
        logger.debug("Parser initial state: " + initialState);
        parseAndCollectTokenSuggestions(initialState, 0);
    }

    /**
     * Recursive through the parser ATN to process all tokens. When successful (out of tokens) - collect completion
     * suggestions.
     */
    private void parseAndCollectTokenSuggestions(ATNState parserState, int tokenListIndex) {
        indent = indent + "  ";
        try {
            logger.debug(indent + "State: " + parserState + " (type: " + parserState.getClass().getSimpleName() + ")");
            logger.debug(indent + "State available transitions: " + transitionsStr(parserState));

            if (!haveMoreTokens(tokenListIndex)) { // stop condition for recursion
                suggestNextTokensForParserState(parserState);
                return;
            }
            for (Transition trans : parserState.getTransitions()) {
                if (trans.isEpsilon()) {
                    handleEpsilonTransition(trans, tokenListIndex);
                } else if (trans instanceof AtomTransition) {
                    handleAtomicTransition((AtomTransition) trans, tokenListIndex);
                } else {
                    // If we ever get SetTransition - should recurse on each of its label.toList() - but could not
                    // simulate this
                    throw new IllegalArgumentException("Unsupported parser transition: " + toString(trans));
                }
            }
        } finally {
            indent = indent.substring(2);
        }
    }

    private boolean haveMoreTokens(int tokenListIndex) {
        return tokenListIndex < inputTokens.size();
    }

    private void handleEpsilonTransition(Transition trans, int tokenListIndex) {
        // Epsilon transitions don't consume a token, so don't move the index
        parseAndCollectTokenSuggestions(trans.target, tokenListIndex);
    }

    private void handleAtomicTransition(AtomTransition trans, int tokenListIndex) {
        Token nextToken = inputTokens.get(tokenListIndex);
        int nextTokenType = inputTokens.get(tokenListIndex).getType();
        boolean nextTokenMatchesTransition = (trans.label == nextTokenType);
        if (nextTokenMatchesTransition) {
            logger.debug(indent + "Token " + nextToken + " following transition: " + toString(trans));
            parseAndCollectTokenSuggestions(trans.target, tokenListIndex + 1);
        } else {
            logger.debug(indent + "Token " + nextToken + " NOT following transition: " + toString(trans));
        }
    }

    private void suggestNextTokensForParserState(ATNState parserState) {
        TokenSuggester tokenSuggester = new TokenSuggester(createLexer());
        Collection<String> suggestions = tokenSuggester.suggest(parserState, this.untokenizedText);
        parseSuggestionsAndAddValidOnes(parserState, suggestions);
        logger.debug(indent + "WILL SUGGEST TOKENS FOR STATE: " + parserState);
    }

    private void parseSuggestionsAndAddValidOnes(ATNState parserState, Collection<String> suggestions) {
        for (String suggestion : suggestions) {
            Token addedToken = getAddedToken(suggestion);
            if (isParseableWithAddedToken(parserState, addedToken)) {
                collectedSuggestions.add(suggestion);
            } else {
                logger.debug("DROPPING non-parseable suggestion: " + suggestion);
            }
        }
    }

    private Token getAddedToken(String suggestedCompletion) {
        String completedText = this.input + suggestedCompletion;
        Lexer completedTextLexer = this.createLexer(completedText);
        completedTextLexer.removeErrorListeners();
        List<? extends Token> completedTextTokens = completedTextLexer.getAllTokens();
        if (completedTextTokens.size() <= inputTokens.size()) {
            return null; // Completion didn't yield whole token, could be just a token fragment
        }
        logger.debug("TOKENS IN COMPLETED TEXT: " + completedTextTokens);
        Token newToken = completedTextTokens.get(completedTextTokens.size() - 1);
        return newToken;
    }

    private boolean isParseableWithAddedToken(ATNState parserState, Token newToken) {
        if (newToken == null) {
            return false;
        }
        for (Transition parserTransition : parserState.getTransitions()) {
            if (parserTransition.isEpsilon()) { // Recurse through any epsilon transitions
                if (isParseableWithAddedToken(parserTransition.target, newToken)) {
                    return true;
                }
            } else if (parserTransition instanceof AtomTransition) {
                AtomTransition parserAtomTransition = (AtomTransition) parserTransition;
                int transitionTokenType = parserAtomTransition.label;
                if (transitionTokenType == newToken.getType()) {
                    return true;
                }
            } else if (parserTransition instanceof SetTransition) {
                SetTransition parserSetTransition = (SetTransition) parserTransition;
                for (int transitionTokenType : parserSetTransition.label().toList()) {
                    if (transitionTokenType == newToken.getType()) {
                        return true;
                    }
                }
            } else {
                throw new IllegalStateException("Unexpected: " + toString(parserTransition));
            }
        }
        return false;
    }

    private String toString(Transition t) {
        return t.getClass().getSimpleName() + "->" + t.target;
    }

    private String transitionsStr(ATNState state) {
        Stream<Transition> transitionsStream = Arrays.asList(state.getTransitions()).stream();
        List<String> transitionStrings = transitionsStream.map(this::toString).collect(Collectors.toList());
        return StringUtils.join(transitionStrings, ", ");
    }

    private static CharStream toCharStream(String text) {
        CharStream inputStream;
        try {
            inputStream = CharStreams.fromReader(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalStateException("Unexpected while reading input string", e);
        }
        return inputStream;
    }

    private Lexer createLexerWithUntokenizedTextDetection() {
        Lexer lexer = createLexer();
        lexer.removeErrorListeners();
        ANTLRErrorListener newErrorListener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String msg, RecognitionException e) throws ParseCancellationException {
                untokenizedText = input.substring(charPositionInLine); // intended side effect
            }
        };
        lexer.addErrorListener(newErrorListener);
        return lexer;
    }
    
    private Lexer createLexer() {
        return createLexer(this.input);
    }

    private Lexer createLexer(String lexerInput) {
        return this.lexerAndParserFactory.createLexer(toCharStream(lexerInput));
    }
}