package org.fentanylsolutions.minemoticon.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class Emoji implements Predicate<String> {

    public String name;
    public List<String> strings = new ArrayList<>();
    public List<String> texts = new ArrayList<>();
    public String location;
    public String category;
    public int version = 1;
    public int sort = 0;

    private String shortString;
    private String regex;
    private Pattern regexPattern;
    private String textRegex;

    @Override
    public boolean test(String s) {
        for (String text : strings) {
            if (s.equalsIgnoreCase(text)) return true;
        }
        return false;
    }

    public String getShorterString() {
        if (shortString != null) return shortString;
        shortString = strings.get(0);
        for (String string : strings) {
            if (string.length() < shortString.length()) {
                shortString = string;
            }
        }
        return shortString;
    }

    public Pattern getRegex() {
        if (regexPattern != null) return regexPattern;
        regexPattern = Pattern.compile(getRegexString());
        return regexPattern;
    }

    public String getRegexString() {
        if (regex != null) return regex;
        List<String> processed = new ArrayList<>();
        for (String string : strings) {
            String s = string;
            char last = Character.toLowerCase(string.charAt(string.length() - 1));
            if (last >= 'a' && last <= 'z') {
                s = s + "\\b";
            }
            char first = Character.toLowerCase(string.charAt(0));
            if (first >= 'a' && first <= 'z') {
                s = "\\b" + s;
            }
            processed.add(cleanForRegex(s));
        }
        regex = String.join("|", processed);
        return regex;
    }

    public String getTextRegex() {
        if (textRegex != null) return textRegex;
        List<String> processed = new ArrayList<>();
        for (String string : texts) {
            processed.add(cleanForRegex(string));
        }
        textRegex = "(?<=^|\\s)(" + String.join("|", processed) + ")(?=$|\\s)";
        return textRegex;
    }

    private static String cleanForRegex(String s) {
        return s.replaceAll("\\)", "\\\\)")
            .replaceAll("\\(", "\\\\(")
            .replaceAll("\\|", "\\\\|")
            .replaceAll("\\*", "\\\\*");
    }
}
