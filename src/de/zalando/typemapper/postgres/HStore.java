package de.zalando.typemapper.postgres;

/**
 * This method parses the content of a postgres DB hstore column and converts it to a hashmap<String, String>
 */

/*
 * Source: https://github.com/zalando/java-sproc-wrapper
 * License Link: https://github.com/zalando/java-sproc-wrapper/blob/master/LICENSE
 * License: 'Licensed under the Apache License, Version 2.0 (the "License")'
 */

import java.sql.Connection;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import org.postgresql.util.PGobject;

import de.zalando.typemapper.parser.exception.HStoreParseException;

public class HStore extends PGobject implements Iterable<Entry<String, String>> {

    private static final long serialVersionUID = -2491617655490561600L;

    private int length;

    /**
     * initialise HStore object and internal store of the hstore content, given as string parameter
     */
    public HStore(final String rawValue) {
        this.type = "hstore";
        this.value = rawValue;
        this.length = rawValue == null ? 0 : rawValue.length();
    }

    public HStore() {
        this.type = "hstore";
        this.length = 0;
    }


    @Override
    public void setValue(final String rawValue) {
        if (!"hstore".equals(this.type)) {
            throw new IllegalStateException("HStore database type name should be 'hstore'");
        }

        this.value = rawValue;
        this.length = rawValue == null ? 0 : rawValue.length();
    }

    /**
     * 
     * @return hstore content returned as Hashmap<String, String>
     */
    public Map<String, String> asMap() {
        HashMap<String, String> r = new HashMap<String, String>();
        try {
            for (final HStoreIterator iterator = new HStoreIterator(); iterator.hasNext();) {
                final HStoreEntry entry = iterator.rawNext();
                r.put(entry.key, entry.value);
            }
        } catch (HStoreParseException e) {
            throw new IllegalStateException(e);
        }

        return r;
    }



    private static class HStoreEntry implements Entry<String, String> {
        private String key;
        private String value;

        HStoreEntry(final String key, final String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public String getValue() {
            return value;
        }

        @Override
        public String setValue(final String value) {
            final String oldValue = this.value;
            this.value = value;
            return oldValue;
        }

    }

    private static enum ParseState {
        WaitingForKey,
        WaitingForEquals,
        WaitingForGreater,
        WaitingForValue,
        WaitingForComma
    }

    private static final char QUOTE = '"';
    private static final char BACKSLASH = '\\';
    private static final char EQUALS = '=';
    private static final char GREATER = '>';
    private static final char COMMA = ',';
    private static final String NULL = "NULL";

    private class HStoreIterator implements Iterator<Entry<String, String>> {

        private int position;
        private HStoreEntry lastReturned;
        private HStoreEntry nextEntry;

        public HStoreIterator() throws HStoreParseException {
            this.position = -1;
            advance();
        }

        @Override
        public boolean hasNext() {
            return nextEntry != null;
        }

        private HStoreEntry rawNext() throws NoSuchElementException, HStoreParseException {
            if (nextEntry == null) {
                throw new NoSuchElementException();
            }

            lastReturned = nextEntry;
            advance();
            return lastReturned;
        }

        @Override
        public Entry<String, String> next() throws NoSuchElementException, IllegalStateException {
            try {
                return rawNext();
            } catch (HStoreParseException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * Advance in parsing the rawValue string and assign the nextValue It creates a new nextElement or assigns null
         * to it, if there are no more elements.
         *
         * @throws  de.zalando.typemapper.parser.exception.HStoreParseException
         */
        private void advance() throws HStoreParseException {
            String elementKey = null;
            String elementValue = null;
            ParseState state = ParseState.WaitingForKey;
            loop:
            while (position < length - 1) {
                final char ch = value.charAt(++position);
                switch (state) {

                    case WaitingForKey :
                        if (Character.isWhitespace(ch)) {
                            continue;
                        }

                        if (ch == QUOTE) {
                            elementKey = advanceQuoted();
                        } else {

                            // we have non-quote char, so start loading the key
                            elementKey = advanceWord(EQUALS);
                            // hstore does not support NULL keys, so NULLs are loaded as usual strings
                        }

                        state = ParseState.WaitingForEquals;
                        continue;

                    case WaitingForEquals :
                        if (Character.isWhitespace(ch)) {
                            continue;
                        }

                        if (ch == EQUALS) {
                            state = ParseState.WaitingForGreater;
                            continue;
                        } else {
                            throw new HStoreParseException("Expected '=>' key-value separator", position);
                        }

                    case WaitingForGreater :
                        if (ch == GREATER) {
                            state = ParseState.WaitingForValue;
                            continue;
                        } else {
                            throw new HStoreParseException("Expected '=>' key-value separator", position);
                        }

                    case WaitingForValue :
                        if (Character.isWhitespace(ch)) {
                            continue;
                        }

                        if (ch == QUOTE) {
                            elementValue = advanceQuoted();
                        } else {

                            // we have non-quote char, so start loading the key
                            elementValue = advanceWord(COMMA);

                            // hstore supports NULL values, so if unquoted NULL is there, it is rewritten to null
                            if (NULL.equalsIgnoreCase(elementValue)) {
                                elementValue = null;
                            }
                        }

                        state = ParseState.WaitingForComma;
                        continue;

                    case WaitingForComma :
                        if (Character.isWhitespace(ch)) {
                            continue;
                        }

                        if (ch == COMMA) {

                            // we are done
                            break loop;
                        } else {
                            throw new HStoreParseException("Cannot find comma as an end of the value: '" + value + "'",
                                position);
                        }

                    default :
                        throw new IllegalStateException("Unknown HStoreParser state");
                }
            } // loop

            // here we either consumed whole string or we found a comma
            if (state == ParseState.WaitingForKey) {

                // string was consumed when waiting for key, so we are done with processing
                nextEntry = null;
                return;
            }

            if (state != ParseState.WaitingForComma) {
                throw new HStoreParseException("Unexpected end of string", position);
            }

            if (elementKey == null) {
                throw new HStoreParseException("Internal parsing error", position);
            }

            // init nextValue
            nextEntry = new HStoreEntry(elementKey, elementValue);
        }

        private String advanceQuoted() throws HStoreParseException {
            final int firstQuotePosition = position;
            StringBuilder sb = null;
            boolean insideQuote = true;
            while (position < length - 1) {
                char ch = value.charAt(++position);
                if (ch == BACKSLASH) {

                    // we saw a backslash, it is either a escaped quote or escaped backslash
                    final int nextPosition = position + 1;
                    if (nextPosition < length) {
                        final char nextCh = value.charAt(nextPosition);
                        if (nextCh == QUOTE) {

                            // it was a escaped quote, so we have to push a quote into the result
                            if (sb == null) {
                                sb = new StringBuilder(value.substring(firstQuotePosition + 1, position));

                            }

                            sb.append(QUOTE);

                            position++;
                            continue;
                        } else if (nextCh == BACKSLASH) {

                            // it was a escaped backslash, so we have to push a "\" into the result
                            if (sb == null) {
                                sb = new StringBuilder(value.substring(firstQuotePosition + 1, position));

                            }

                            sb.append(BACKSLASH);

                            position++;
                            continue;
                        }
                    }

                    throw new HStoreParseException("Backslash without following backslash or quote at position "
                            + position, position);

                } else if (ch == QUOTE) {

                    // it was a closing quote as we either ware are at the end of the rawValue string
                    // or we could not find the next quote
                    insideQuote = false;
                    break;
                } else {
                    if (sb != null) {
                        sb.append(ch);
                    }
                }
            }

            if (insideQuote) {
                throw new HStoreParseException("Quote at string position " + firstQuotePosition + " is not closed",
                    position);
            }

            if (sb == null) {

                // we consumed the last quote
                String r = value.substring(firstQuotePosition + 1, position);
                return r;
            } else {
                return sb.toString();
            }
        }

        private String advanceWord(final char stopAtChar) throws HStoreParseException {
            final int firstWordPosition = position;
            while (position < length) {
                final char ch = value.charAt(position);
                if (ch == QUOTE) {
                    throw new HStoreParseException("Unexpected quote in word", position);
                } else if (Character.isWhitespace(ch) || ch == stopAtChar) {
                    break;
                }

                position++;
            }

            // step back as we are already one char away
            position--;

            // substring is using quite a strange way of defining end position
            final String r = value.substring(firstWordPosition, position + 1);
            return r;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public Iterator<Entry<String, String>> iterator() {
        try {
            return new HStoreIterator();
        } catch (HStoreParseException e) {
            throw new IllegalStateException(e);
        }
    }

}
