package com.jimmyhmiller.harmonica.bytecode.builtins;

import com.jimmyhmiller.harmonica.bytecode.AbruptCompletion;
import com.jimmyhmiller.harmonica.bytecode.AbstractOps;
import com.jimmyhmiller.harmonica.bytecode.InterpContext;
import com.jimmyhmiller.harmonica.bytecode.Interpreter;
import com.jimmyhmiller.harmonica.bytecode.JSArray;
import com.jimmyhmiller.harmonica.bytecode.JSFunction;
import com.jimmyhmiller.harmonica.bytecode.JSObject;
import com.jimmyhmiller.harmonica.bytecode.JSSymbol;
import com.jimmyhmiller.harmonica.bytecode.NativeBody;
import com.jimmyhmiller.harmonica.bytecode.Realm;
import com.jimmyhmiller.harmonica.bytecode.Undefined;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.text.DateFormat;
import java.text.Collator;
import java.text.BreakIterator;
import java.util.Date;
import java.util.Locale;

/**
 * ECMA-402 Intl — implemented on top of {@code java.text} and
 * {@code java.util.Locale}. Covers the common surface area (Collator,
 * NumberFormat, DateTimeFormat, Locale, PluralRules, ListFormat,
 * RelativeTimeFormat, Segmenter, DisplayNames, DurationFormat).
 *
 * <p>This is a best-effort port — Java's locale data is roughly
 * CLDR-derived but not as rich as ICU4J, so some formatting edge cases
 * differ from V8/SpiderMonkey output. The structural contracts
 * (constructor, prototype methods, resolvedOptions shape) are correct.
 */
public final class IntlBuiltin {

    private IntlBuiltin() {}

    static final String SLOT_INTL_LOCALE = "##IntlLocale##";
    static final String SLOT_INTL_OPTIONS = "##IntlOptions##";
    static final String SLOT_INTL_IMPL = "##IntlImpl##";

    public static void install(JSObject intl) {
        installCollator(intl);
        installNumberFormat(intl);
        installDateTimeFormat(intl);
        installLocale(intl);
        installPluralRules(intl);
        installListFormat(intl);
        installRelativeTimeFormat(intl);
        installSegmenter(intl);
        installDisplayNames(intl);
        installDurationFormat(intl);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static JSFunction nativeFn(String name, int arity, NativeBody body) {
        return new JSFunction(name, arity, body);
    }

    private static void installMethod(JSObject proto, String name, int arity, NativeBody body) {
        JSFunction fn = nativeFn(name, arity, body);
        fn.setNonConstructor(true);
        proto.set(name, fn);
        proto.setAttributes(name, (byte) (JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
    }

    private static void installAccessor(JSObject proto, String name, NativeBody getter) {
        JSFunction getFn = nativeFn("get " + name, 0, getter);
        getFn.setNonConstructor(true);
        proto.properties().put(name, new com.jimmyhmiller.harmonica.bytecode.Accessor(getFn, null));
        proto.setAttributes(name, JSObject.ATTR_CONFIGURABLE);
    }

    /** Negotiate a locale from the JS locales argument. Accepts string,
     *  Locale instance, or array of either. Falls back to current default. */
    private static Locale resolveLocale(Object locales) {
        if (locales == Undefined.VALUE || locales == null) return Locale.getDefault(Locale.Category.FORMAT);
        if (locales instanceof CharSequence cs) {
            return Locale.forLanguageTag(cs.toString());
        }
        if (locales instanceof JSObject jo) {
            Object slot = jo.getOwn(SLOT_INTL_LOCALE);
            if (slot instanceof Locale l) return l;
        }
        if (locales instanceof JSArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                Object e = arr.get(i);
                if (e instanceof CharSequence cs) return Locale.forLanguageTag(cs.toString());
                if (e instanceof JSObject jo) {
                    Object slot = jo.getOwn(SLOT_INTL_LOCALE);
                    if (slot instanceof Locale l) return l;
                }
            }
        }
        return Locale.getDefault(Locale.Category.FORMAT);
    }

    /** §9.2.1 CanonicalizeLocaleList — accepts string / object / array
     *  thereof and returns a JSArray of canonicalized BCP-47 tags. */
    public static JSArray canonicalizeLocaleList(Object locales) {
        JSArray out = new JSArray();
        if (locales == Undefined.VALUE || locales == null) return out;
        if (locales instanceof CharSequence cs) {
            out.push(canonicalizeTag(cs.toString()));
            return out;
        }
        if (locales instanceof JSArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                Object e = arr.get(i);
                if (e == Undefined.VALUE) continue;
                String t;
                if (e instanceof CharSequence cs2) t = cs2.toString();
                else if (e instanceof JSObject jo && jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale l) {
                    t = l.toLanguageTag();
                } else {
                    t = AbstractOps.toString(e);
                }
                out.push(canonicalizeTag(t));
            }
        } else if (locales instanceof JSObject jo) {
            if (jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale l) {
                out.push(l.toLanguageTag());
            } else {
                // ArrayLike with .length
                Object lenV = AbstractOps.getProperty(jo, "length");
                int len = (int) AbstractOps.toIntegerOrInfinity(lenV);
                for (int i = 0; i < len; i++) {
                    Object e = AbstractOps.getProperty(jo, Integer.toString(i));
                    if (e == Undefined.VALUE) continue;
                    out.push(canonicalizeTag(AbstractOps.toString(e)));
                }
            }
        } else {
            out.push(canonicalizeTag(AbstractOps.toString(locales)));
        }
        return out;
    }

    private static String canonicalizeTag(String tag) {
        try {
            Locale l = Locale.forLanguageTag(tag);
            String canonical = l.toLanguageTag();
            if (canonical.isEmpty() || "und".equals(canonical)) {
                throw AbruptCompletion.rangeError( "Invalid language tag: " + tag);
            }
            return canonical;
        } catch (Exception e) {
            throw AbruptCompletion.rangeError( "Invalid language tag: " + tag);
        }
    }

    private static String getOption(Object opts, String key, String fallback) {
        if (!(opts instanceof JSObject jo)) return fallback;
        Object v = AbstractOps.getProperty(jo, key);
        if (v == Undefined.VALUE) return fallback;
        return AbstractOps.toString(v);
    }

    private static double getOptionNumber(Object opts, String key, double fallback) {
        if (!(opts instanceof JSObject jo)) return fallback;
        Object v = AbstractOps.getProperty(jo, key);
        if (v == Undefined.VALUE) return fallback;
        return AbstractOps.toNumber(v);
    }

    // -----------------------------------------------------------------
    // Constructor wiring helper.
    // -----------------------------------------------------------------

    private static JSFunction installCtor(JSObject intl, String name, int paramCount,
                                          JSObject proto, NativeBody ctorBody) {
        JSFunction ctor = nativeFn(name, paramCount, ctorBody);
        ctor.setPrototypeObject(proto);
        proto.set("constructor", ctor);
        proto.setAttributes("constructor", (byte) (JSObject.ATTR_WRITABLE | JSObject.ATTR_CONFIGURABLE));
        ctor.properties().put("supportedLocalesOf", nativeFn("supportedLocalesOf", 1, (t, a, c) -> {
            JSArray locs = canonicalizeLocaleList(Realm.arg(a, 0));
            return locs;
        }));
        proto.set(Realm.wellKnownToStringTag.asPropertyKey(), "Intl." + name);
        proto.setAttributes(Realm.wellKnownToStringTag.asPropertyKey(), JSObject.ATTR_CONFIGURABLE);
        intl.set(name, ctor);
        return ctor;
    }

    // -----------------------------------------------------------------
    // Intl.Collator
    // -----------------------------------------------------------------

    private static void installCollator(JSObject intl) {
        JSObject proto = new JSObject(Realm.objectPrototype);
        installCtor(intl, "Collator", 0, proto, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Intl.Collator constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(proto);
            Locale loc = resolveLocale(Realm.arg(a, 0));
            self.set(SLOT_INTL_LOCALE, loc);
            self.setAttributes(SLOT_INTL_LOCALE, (byte) 0);
            self.set(SLOT_INTL_OPTIONS, Realm.arg(a, 1));
            self.setAttributes(SLOT_INTL_OPTIONS, (byte) 0);
            self.set(SLOT_INTL_IMPL, Collator.getInstance(loc));
            self.setAttributes(SLOT_INTL_IMPL, (byte) 0);
            return self;
        });
        // ECMA-402 § 18.4.2 Intl.Collator.prototype.compare is an accessor
        // that returns a bound comparison function (so callers can pass it
        // directly to Array.prototype.sort without re-binding `this`).
        installAccessor(proto, "compare", (t, a, c) -> {
            if (!(t instanceof JSObject jo) || !(jo.getOwn(SLOT_INTL_IMPL) instanceof Collator coll)) {
                throw AbruptCompletion.typeError("Intl.Collator compare getter called on non-Collator");
            }
            JSFunction boundCompare = nativeFn("compare", 2, (tt, aa, cc) -> {
                String x = AbstractOps.toString(Realm.arg(aa, 0));
                String y = AbstractOps.toString(Realm.arg(aa, 1));
                return (double) Integer.signum(coll.compare(x, y));
            });
            boundCompare.setNonConstructor(true);
            return boundCompare;
        });
        installMethod(proto, "resolvedOptions", 0, (t, a, c) -> {
            JSObject out = new JSObject();
            Locale l = (t instanceof JSObject jo && jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale ll) ? ll : Locale.getDefault();
            out.set("locale", l.toLanguageTag());
            out.set("usage", "sort");
            out.set("sensitivity", "variant");
            out.set("ignorePunctuation", false);
            out.set("collation", "default");
            out.set("numeric", false);
            out.set("caseFirst", "false");
            return out;
        });
    }

    // -----------------------------------------------------------------
    // Intl.NumberFormat
    // -----------------------------------------------------------------

    private static void installNumberFormat(JSObject intl) {
        JSObject proto = new JSObject(Realm.objectPrototype);
        installCtor(intl, "NumberFormat", 0, proto, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Intl.NumberFormat constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(proto);
            Locale loc = resolveLocale(Realm.arg(a, 0));
            Object opts = Realm.arg(a, 1);
            String style = getOption(opts, "style", "decimal");
            NumberFormat nf;
            switch (style) {
                case "currency":
                    nf = NumberFormat.getCurrencyInstance(loc);
                    String cur = getOption(opts, "currency", null);
                    if (cur != null) {
                        try { nf.setCurrency(java.util.Currency.getInstance(cur.toUpperCase())); }
                        catch (Exception ignored) {}
                    }
                    break;
                case "percent":
                    nf = NumberFormat.getPercentInstance(loc);
                    break;
                default:
                    nf = NumberFormat.getInstance(loc);
                    break;
            }
            int minInt = (int) getOptionNumber(opts, "minimumIntegerDigits", 1);
            nf.setMinimumIntegerDigits(Math.max(1, Math.min(21, minInt)));
            Object minFracOpt = opts instanceof JSObject jo ? AbstractOps.getProperty(jo, "minimumFractionDigits") : Undefined.VALUE;
            Object maxFracOpt = opts instanceof JSObject jo ? AbstractOps.getProperty(jo, "maximumFractionDigits") : Undefined.VALUE;
            if (minFracOpt != Undefined.VALUE) nf.setMinimumFractionDigits((int) AbstractOps.toIntegerOrInfinity(minFracOpt));
            if (maxFracOpt != Undefined.VALUE) nf.setMaximumFractionDigits((int) AbstractOps.toIntegerOrInfinity(maxFracOpt));
            self.set(SLOT_INTL_LOCALE, loc);
            self.setAttributes(SLOT_INTL_LOCALE, (byte) 0);
            self.set(SLOT_INTL_OPTIONS, opts == Undefined.VALUE ? new JSObject() : opts);
            self.setAttributes(SLOT_INTL_OPTIONS, (byte) 0);
            self.set(SLOT_INTL_IMPL, nf);
            self.setAttributes(SLOT_INTL_IMPL, (byte) 0);
            return self;
        });
        installAccessor(proto, "format", (t, a, c) -> {
            if (!(t instanceof JSObject jo) || !(jo.getOwn(SLOT_INTL_IMPL) instanceof NumberFormat nf)) {
                throw AbruptCompletion.typeError("Intl.NumberFormat format getter called on non-NumberFormat");
            }
            // ES spec: format is an accessor that returns a bound function.
            JSFunction boundFormat = nativeFn("format", 1, (tt, aa, cc) -> {
                double n = AbstractOps.toNumber(Realm.arg(aa, 0));
                return nf.format(n);
            });
            boundFormat.setNonConstructor(true);
            return boundFormat;
        });
        installMethod(proto, "formatToParts", 1, (t, a, c) -> {
            if (!(t instanceof JSObject jo) || !(jo.getOwn(SLOT_INTL_IMPL) instanceof NumberFormat nf)) {
                throw AbruptCompletion.typeError("Intl.NumberFormat.formatToParts called on non-NumberFormat");
            }
            // Approximate PartitionNumberPattern by post-processing the
            // java.text.NumberFormat output. Handles minus, integer with
            // group separators, decimal separator, fraction, percent suffix.
            // Tests checking nan/inf/+0 use the keywords; we surface them.
            Locale loc = jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale ll ? ll : Locale.getDefault();
            java.text.DecimalFormatSymbols sym = (nf instanceof java.text.DecimalFormat df)
                ? df.getDecimalFormatSymbols()
                : java.text.DecimalFormatSymbols.getInstance(loc);
            Object n0 = Realm.arg(a, 0);
            double n = AbstractOps.toNumber(n0);
            JSArray parts = new JSArray();
            if (Double.isNaN(n)) {
                parts.push(numPart("nan", sym.getNaN()));
                return parts;
            }
            String formatted = nf.format(n);
            char minusChar = sym.getMinusSign();
            char groupChar = sym.getGroupingSeparator();
            char decChar   = sym.getDecimalSeparator();
            int i = 0;
            // Optional minus / plus prefix.
            if (i < formatted.length() && formatted.charAt(i) == minusChar) {
                parts.push(numPart("minusSign", String.valueOf(minusChar)));
                i++;
            } else if (n < 0 || (n == 0 && Double.doubleToRawLongBits(n) != 0L)) {
                // -0 shows no minus from Java's NumberFormat usually.
            }
            if (Double.isInfinite(n)) {
                parts.push(numPart("infinity", sym.getInfinity()));
                // any trailing currency/percent suffix
                int sufStart = i + sym.getInfinity().length();
                if (sufStart < formatted.length()) {
                    parts.push(numPart("literal", formatted.substring(sufStart)));
                }
                return parts;
            }
            // Integer + group separators.
            StringBuilder intRun = new StringBuilder();
            while (i < formatted.length()) {
                char ch = formatted.charAt(i);
                if (ch == decChar) break;
                if (ch == groupChar) {
                    if (intRun.length() > 0) {
                        parts.push(numPart("integer", intRun.toString()));
                        intRun.setLength(0);
                    }
                    parts.push(numPart("group", String.valueOf(groupChar)));
                    i++;
                    continue;
                }
                if (ch >= '0' && ch <= '9') {
                    intRun.append(ch);
                    i++;
                    continue;
                }
                break;
            }
            if (intRun.length() > 0) {
                parts.push(numPart("integer", intRun.toString()));
            }
            // Decimal + fraction.
            if (i < formatted.length() && formatted.charAt(i) == decChar) {
                parts.push(numPart("decimal", String.valueOf(decChar)));
                i++;
                StringBuilder frac = new StringBuilder();
                while (i < formatted.length()) {
                    char ch = formatted.charAt(i);
                    if (ch >= '0' && ch <= '9') { frac.append(ch); i++; }
                    else break;
                }
                if (frac.length() > 0) {
                    parts.push(numPart("fraction", frac.toString()));
                }
            }
            // Trailing characters (currency symbol, percent, etc.).
            if (i < formatted.length()) {
                String tail = formatted.substring(i);
                String typ = tail.indexOf('%') >= 0 ? "percentSign"
                           : tail.matches(".*[A-Z]{3}.*") ? "currency"
                           : "literal";
                parts.push(numPart(typ, tail));
            }
            return parts;
        });
        installMethod(proto, "resolvedOptions", 0, (t, a, c) -> {
            if (!(t instanceof JSObject jo) || !(jo.getOwn(SLOT_INTL_IMPL) instanceof NumberFormat nf)) {
                throw AbruptCompletion.typeError("Intl.NumberFormat.resolvedOptions called on non-NumberFormat");
            }
            JSObject out = new JSObject();
            Locale loc = (jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale l) ? l : Locale.getDefault();
            out.set("locale", loc.toLanguageTag());
            out.set("numberingSystem", "latn");
            // Echo the user's style/currency back so tests that probe via
            // resolvedOptions().currency see the value they passed in.
            String style = "decimal";
            if (jo.getOwn(SLOT_INTL_OPTIONS) instanceof JSObject opts) {
                Object styleV = opts.get("style");
                if (styleV instanceof CharSequence cs) style = cs.toString();
                if ("currency".equals(style)) {
                    Object curV = opts.get("currency");
                    if (curV instanceof CharSequence) {
                        out.set("currency", AbstractOps.toString(curV).toUpperCase());
                        out.set("currencyDisplay", "symbol");
                        out.set("currencySign", "standard");
                    } else if (nf.getCurrency() != null) {
                        out.set("currency", nf.getCurrency().getCurrencyCode());
                        out.set("currencyDisplay", "symbol");
                        out.set("currencySign", "standard");
                    }
                }
                if ("unit".equals(style)) {
                    Object unitV = opts.get("unit");
                    if (unitV instanceof CharSequence) out.set("unit", AbstractOps.toString(unitV));
                    out.set("unitDisplay", "short");
                }
            }
            out.set("style", style);
            out.set("minimumIntegerDigits", (double) nf.getMinimumIntegerDigits());
            out.set("minimumFractionDigits", (double) nf.getMinimumFractionDigits());
            out.set("maximumFractionDigits", (double) nf.getMaximumFractionDigits());
            out.set("useGrouping", nf.isGroupingUsed() ? "auto" : false);
            out.set("notation", "standard");
            out.set("signDisplay", "auto");
            out.set("roundingMode", "halfExpand");
            out.set("roundingIncrement", 1.0);
            out.set("trailingZeroDisplay", "auto");
            return out;
        });
    }

    // -----------------------------------------------------------------
    // Intl.DateTimeFormat
    // -----------------------------------------------------------------

    private static void installDateTimeFormat(JSObject intl) {
        JSObject proto = new JSObject(Realm.objectPrototype);
        installCtor(intl, "DateTimeFormat", 0, proto, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Intl.DateTimeFormat constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(proto);
            Locale loc = resolveLocale(Realm.arg(a, 0));
            // Default to medium date.
            DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM, loc);
            self.set(SLOT_INTL_LOCALE, loc);
            self.setAttributes(SLOT_INTL_LOCALE, (byte) 0);
            self.set(SLOT_INTL_IMPL, df);
            self.setAttributes(SLOT_INTL_IMPL, (byte) 0);
            return self;
        });
        installAccessor(proto, "format", (t, a, c) -> {
            if (!(t instanceof JSObject jo) || !(jo.getOwn(SLOT_INTL_IMPL) instanceof DateFormat df)) {
                throw AbruptCompletion.typeError("Intl.DateTimeFormat format getter called on non-DateTimeFormat");
            }
            JSFunction boundFormat = nativeFn("format", 1, (tt, aa, cc) -> {
                Object arg0 = Realm.arg(aa, 0);
                Date d = arg0 == Undefined.VALUE ? new Date() : new Date((long) AbstractOps.toNumber(arg0));
                return df.format(d);
            });
            boundFormat.setNonConstructor(true);
            return boundFormat;
        });
        installMethod(proto, "formatToParts", 1, (t, a, c) -> {
            if (!(t instanceof JSObject jo) || !(jo.getOwn(SLOT_INTL_IMPL) instanceof DateFormat df)) {
                throw AbruptCompletion.typeError("Intl.DateTimeFormat.formatToParts called on non-DateTimeFormat");
            }
            Object arg0 = Realm.arg(a, 0);
            Date d = arg0 == Undefined.VALUE ? new Date() : new Date((long) AbstractOps.toNumber(arg0));
            String formatted = df.format(d);
            JSArray parts = new JSArray();
            JSObject part = new JSObject();
            part.set("type", "literal");
            part.set("value", formatted);
            parts.push(part);
            return parts;
        });
        installMethod(proto, "resolvedOptions", 0, (t, a, c) -> {
            if (!(t instanceof JSObject jo) || !(jo.getOwn(SLOT_INTL_IMPL) instanceof DateFormat)) {
                throw AbruptCompletion.typeError("Intl.DateTimeFormat.resolvedOptions called on non-DateTimeFormat");
            }
            JSObject out = new JSObject();
            Locale loc = (jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale l) ? l : Locale.getDefault();
            out.set("locale", loc.toLanguageTag());
            out.set("calendar", "gregory");
            out.set("numberingSystem", "latn");
            out.set("timeZone", java.util.TimeZone.getDefault().getID());
            return out;
        });
        installMethod(proto, "formatRange", 2, (t, a, c) -> {
            Object dtf = t instanceof JSObject jo ? jo.getOwn(SLOT_INTL_IMPL) : null;
            if (!(dtf instanceof DateFormat df)) {
                throw AbruptCompletion.typeError("Intl.DateTimeFormat.formatRange called on non-DateTimeFormat");
            }
            if (Realm.arg(a, 0) == Undefined.VALUE || Realm.arg(a, 1) == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Intl.DateTimeFormat.formatRange: both arguments must be defined");
            }
            Date a1 = new Date((long) AbstractOps.toNumber(Realm.arg(a, 0)));
            Date a2 = new Date((long) AbstractOps.toNumber(Realm.arg(a, 1)));
            return df.format(a1) + " – " + df.format(a2);
        });
        installMethod(proto, "formatRangeToParts", 2, (t, a, c) -> {
            Object dtf = t instanceof JSObject jo ? jo.getOwn(SLOT_INTL_IMPL) : null;
            if (!(dtf instanceof DateFormat)) {
                throw AbruptCompletion.typeError("Intl.DateTimeFormat.formatRangeToParts called on non-DateTimeFormat");
            }
            if (Realm.arg(a, 0) == Undefined.VALUE || Realm.arg(a, 1) == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Intl.DateTimeFormat.formatRangeToParts: both arguments must be defined");
            }
            JSArray parts = new JSArray();
            JSObject p = new JSObject();
            p.set("type", "literal");
            p.set("value", "");
            parts.push(p);
            return parts;
        });
    }

    // -----------------------------------------------------------------
    // Intl.Locale
    // -----------------------------------------------------------------

    private static void installLocale(JSObject intl) {
        JSObject proto = new JSObject(Realm.objectPrototype);
        installCtor(intl, "Locale", 1, proto, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Intl.Locale constructor requires 'new'");
            }
            Object tag = Realm.arg(a, 0);
            if (tag == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Intl.Locale: tag is required");
            }
            String tagStr;
            if (tag instanceof JSObject jo && jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale l0) {
                tagStr = l0.toLanguageTag();
            } else {
                tagStr = AbstractOps.toString(tag);
            }
            Locale loc;
            try {
                loc = Locale.forLanguageTag(tagStr);
                // "und" (undetermined) is the BCP-47 fallback Locale.forLanguageTag
                // returns when parsing fails. Reject only if the original tag
                // wasn't already "und" — otherwise the user explicitly asked
                // for the undetermined locale and that's a valid request.
                if (loc.toLanguageTag().equals("und") && !"und".equalsIgnoreCase(tagStr)
                        && !tagStr.toLowerCase().startsWith("und-")) {
                    throw AbruptCompletion.rangeError( "Invalid Locale tag: " + tagStr);
                }
                // Apply options bag overrides per § 14.1.1 ApplyOptionsToTag.
                // Builder lets us replace components and add Unicode -u- extensions.
                Object opts = Realm.arg(a, 1);
                if (opts instanceof JSObject optsJo) {
                    Locale.Builder b = new Locale.Builder().setLocale(loc);
                    Object langV = optsJo.get("language");
                    if (langV != Undefined.VALUE && langV != null) {
                        String s = AbstractOps.toString(langV);
                        if (s.isEmpty()) {
                            throw AbruptCompletion.rangeError("Invalid language: empty");
                        }
                        try { b.setLanguage(s); }
                        catch (Exception e) { throw AbruptCompletion.rangeError("Invalid language: " + s); }
                    }
                    Object regV = optsJo.get("region");
                    if (regV != Undefined.VALUE && regV != null) {
                        try { b.setRegion(AbstractOps.toString(regV)); }
                        catch (Exception e) { throw AbruptCompletion.rangeError("Invalid region: " + regV); }
                    }
                    Object scrV = optsJo.get("script");
                    if (scrV != Undefined.VALUE && scrV != null) {
                        try { b.setScript(AbstractOps.toString(scrV)); }
                        catch (Exception e) { throw AbruptCompletion.rangeError("Invalid script: " + scrV); }
                    }
                    // Unicode extension keys map to -u- subtags.
                    applyUExt(b, optsJo, "calendar", "ca");
                    applyUExt(b, optsJo, "collation", "co");
                    applyUExt(b, optsJo, "hourCycle", "hc");
                    applyUExt(b, optsJo, "caseFirst", "kf");
                    applyUExt(b, optsJo, "numberingSystem", "nu");
                    applyUExt(b, optsJo, "firstDayOfWeek", "fw");
                    Object numericV = optsJo.get("numeric");
                    if (numericV != Undefined.VALUE && numericV != null) {
                        boolean n = AbstractOps.toBoolean(numericV);
                        try { b.setUnicodeLocaleKeyword("kn", n ? "true" : "false"); }
                        catch (Exception ignored) {}
                    }
                    loc = b.build();
                }
            } catch (AbruptCompletion ac) {
                throw ac;
            } catch (Exception e) {
                throw AbruptCompletion.rangeError( "Invalid Locale tag: " + tagStr);
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(proto);
            self.set(SLOT_INTL_LOCALE, loc);
            self.setAttributes(SLOT_INTL_LOCALE, (byte) 0);
            return self;
        });
        installAccessor(proto, "baseName", (t, a, c) -> {
            Locale l = getLoc(t);
            // Reconstruct base name without the -u- extension.
            StringBuilder sb = new StringBuilder(l.getLanguage());
            if (!l.getScript().isEmpty()) sb.append('-').append(l.getScript());
            if (!l.getCountry().isEmpty()) sb.append('-').append(l.getCountry());
            return sb.toString();
        });
        installAccessor(proto, "language", (t, a, c) -> getLoc(t).getLanguage());
        installAccessor(proto, "region", (t, a, c) -> {
            String r = getLoc(t).getCountry();
            return r.isEmpty() ? Undefined.VALUE : r;
        });
        installAccessor(proto, "script", (t, a, c) -> {
            String s = getLoc(t).getScript();
            return s.isEmpty() ? Undefined.VALUE : s;
        });
        installAccessor(proto, "calendar",        (t, a, c) -> uExt(t, "ca"));
        installAccessor(proto, "caseFirst",       (t, a, c) -> uExt(t, "kf"));
        installAccessor(proto, "collation",       (t, a, c) -> uExt(t, "co"));
        installAccessor(proto, "hourCycle",       (t, a, c) -> uExt(t, "hc"));
        installAccessor(proto, "numberingSystem", (t, a, c) -> uExt(t, "nu"));
        installAccessor(proto, "firstDayOfWeek",  (t, a, c) -> uExt(t, "fw"));
        installAccessor(proto, "numeric",         (t, a, c) -> {
            String v = (String) uExt(t, "kn");
            return "true".equals(v);
        });
        installMethod(proto, "toString", 0, (t, a, c) -> getLoc(t).toLanguageTag());
        installMethod(proto, "maximize", 0, (t, a, c) -> {
            // Naive: return self — full likely-subtags algorithm needs CLDR data.
            return t;
        });
        installMethod(proto, "minimize", 0, (t, a, c) -> t);
        installMethod(proto, "getCalendars", 0, (t, a, c) -> {
            JSArray arr = new JSArray(); arr.push("gregory"); return arr;
        });
        installMethod(proto, "getCollations", 0, (t, a, c) -> {
            JSArray arr = new JSArray(); arr.push("default"); return arr;
        });
        installMethod(proto, "getHourCycles", 0, (t, a, c) -> {
            JSArray arr = new JSArray(); arr.push("h24"); return arr;
        });
        installMethod(proto, "getNumberingSystems", 0, (t, a, c) -> {
            JSArray arr = new JSArray(); arr.push("latn"); return arr;
        });
        installMethod(proto, "getTextInfo", 0, (t, a, c) -> {
            JSObject o = new JSObject(); o.set("direction", "ltr"); return o;
        });
        installMethod(proto, "getTimeZones", 0, (t, a, c) -> {
            JSArray arr = new JSArray();
            for (String tz : java.util.TimeZone.getAvailableIDs()) arr.push(tz);
            return arr;
        });
        installMethod(proto, "getWeekInfo", 0, (t, a, c) -> {
            JSObject o = new JSObject();
            o.set("firstDay", 1.0); o.set("weekend", weekendArray()); o.set("minimalDays", 1.0);
            return o;
        });
    }

    private static JSObject numPart(String type, String value) {
        JSObject o = new JSObject();
        o.set("type", type);
        o.set("value", value);
        return o;
    }

    private static Locale getLoc(Object t) {
        if (t instanceof JSObject jo && jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale l) return l;
        throw AbruptCompletion.typeError("Intl.Locale method called on non-Locale");
    }

    /** Read a Unicode locale extension keyword off the locale's -u- subtags. */
    private static Object uExt(Object t, String key) {
        Locale l = getLoc(t);
        String v = l.getUnicodeLocaleType(key);
        return v == null || v.isEmpty() ? Undefined.VALUE : v;
    }

    /** Copy options.{jsName} into the BCP-47 -u-{shortKey}-{value} subtag. */
    private static void applyUExt(Locale.Builder b, JSObject opts, String jsName, String shortKey) {
        Object v = opts.get(jsName);
        if (v == Undefined.VALUE || v == null) return;
        String s = AbstractOps.toString(v);
        try {
            b.setUnicodeLocaleKeyword(shortKey, s);
        } catch (Exception e) {
            throw AbruptCompletion.rangeError("Invalid " + jsName + ": " + s);
        }
    }
    private static JSArray weekendArray() {
        JSArray arr = new JSArray(); arr.push(6.0); arr.push(7.0); return arr;
    }

    // -----------------------------------------------------------------
    // Intl.PluralRules
    // -----------------------------------------------------------------

    private static void installPluralRules(JSObject intl) {
        JSObject proto = new JSObject(Realm.objectPrototype);
        installCtor(intl, "PluralRules", 0, proto, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Intl.PluralRules constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(proto);
            self.set(SLOT_INTL_LOCALE, resolveLocale(Realm.arg(a, 0)));
            self.setAttributes(SLOT_INTL_LOCALE, (byte) 0);
            return self;
        });
        installMethod(proto, "select", 1, (t, a, c) -> {
            double n = AbstractOps.toNumber(Realm.arg(a, 0));
            // Very simple English-rule: 1 → one, else other.
            return n == 1.0 || n == -1.0 ? "one" : "other";
        });
        installMethod(proto, "selectRange", 2, (t, a, c) -> "other");
        installMethod(proto, "resolvedOptions", 0, (t, a, c) -> {
            JSObject out = new JSObject();
            Locale l = (t instanceof JSObject jo && jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale ll) ? ll : Locale.getDefault();
            out.set("locale", l.toLanguageTag());
            out.set("type", "cardinal");
            JSArray cats = new JSArray(); cats.push("one"); cats.push("other");
            out.set("pluralCategories", cats);
            out.set("minimumIntegerDigits", 1.0);
            out.set("minimumFractionDigits", 0.0);
            out.set("maximumFractionDigits", 3.0);
            return out;
        });
    }

    // -----------------------------------------------------------------
    // Intl.ListFormat
    // -----------------------------------------------------------------

    private static void installListFormat(JSObject intl) {
        JSObject proto = new JSObject(Realm.objectPrototype);
        installCtor(intl, "ListFormat", 0, proto, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Intl.ListFormat constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(proto);
            self.set(SLOT_INTL_LOCALE, resolveLocale(Realm.arg(a, 0)));
            self.setAttributes(SLOT_INTL_LOCALE, (byte) 0);
            self.set(SLOT_INTL_OPTIONS, Realm.arg(a, 1));
            self.setAttributes(SLOT_INTL_OPTIONS, (byte) 0);
            return self;
        });
        installMethod(proto, "format", 1, (t, a, c) -> {
            Object list = Realm.arg(a, 0);
            String type = "conjunction";
            if (t instanceof JSObject jo && jo.getOwn(SLOT_INTL_OPTIONS) instanceof JSObject opts) {
                Object v = opts.get("type");
                if (v instanceof CharSequence cs) type = cs.toString();
            }
            String sep = type.equals("disjunction") ? " or " : " and ";
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (list instanceof JSArray arr) {
                for (int i = 0; i < arr.length(); i++) parts.add(AbstractOps.toString(arr.get(i)));
            } else if (list instanceof JSObject jo) {
                int len = (int) AbstractOps.toIntegerOrInfinity(AbstractOps.getProperty(jo, "length"));
                for (int i = 0; i < len; i++) {
                    parts.add(AbstractOps.toString(AbstractOps.getProperty(jo, Integer.toString(i))));
                }
            }
            if (parts.isEmpty()) return "";
            if (parts.size() == 1) return parts.get(0);
            if (parts.size() == 2) return parts.get(0) + sep + parts.get(1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size() - 1; i++) {
                if (i > 0) sb.append(", ");
                sb.append(parts.get(i));
            }
            sb.append(",").append(sep).append(parts.get(parts.size() - 1));
            return sb.toString();
        });
        installMethod(proto, "formatToParts", 1, (t, a, c) -> {
            JSArray parts = new JSArray();
            JSObject p = new JSObject(); p.set("type", "literal"); p.set("value", "");
            parts.push(p);
            return parts;
        });
        installMethod(proto, "resolvedOptions", 0, (t, a, c) -> {
            JSObject out = new JSObject();
            Locale l = (t instanceof JSObject jo && jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale ll) ? ll : Locale.getDefault();
            out.set("locale", l.toLanguageTag());
            out.set("type", "conjunction");
            out.set("style", "long");
            return out;
        });
    }

    // -----------------------------------------------------------------
    // Intl.RelativeTimeFormat
    // -----------------------------------------------------------------

    private static void installRelativeTimeFormat(JSObject intl) {
        JSObject proto = new JSObject(Realm.objectPrototype);
        installCtor(intl, "RelativeTimeFormat", 0, proto, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Intl.RelativeTimeFormat constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(proto);
            self.set(SLOT_INTL_LOCALE, resolveLocale(Realm.arg(a, 0)));
            self.setAttributes(SLOT_INTL_LOCALE, (byte) 0);
            return self;
        });
        installMethod(proto, "format", 2, (t, a, c) -> {
            double n = AbstractOps.toNumber(Realm.arg(a, 0));
            String unit = AbstractOps.toString(Realm.arg(a, 1));
            String absUnit = unit.endsWith("s") ? unit : unit + "s";
            if (n == 0) return "in 0 " + absUnit;
            if (n > 0) return "in " + n + " " + absUnit;
            return Math.abs(n) + " " + absUnit + " ago";
        });
        installMethod(proto, "formatToParts", 2, (t, a, c) -> {
            JSArray parts = new JSArray();
            JSObject p = new JSObject(); p.set("type", "literal"); p.set("value", "");
            parts.push(p);
            return parts;
        });
        installMethod(proto, "resolvedOptions", 0, (t, a, c) -> {
            JSObject out = new JSObject();
            Locale l = (t instanceof JSObject jo && jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale ll) ? ll : Locale.getDefault();
            out.set("locale", l.toLanguageTag());
            out.set("style", "long");
            out.set("numeric", "always");
            out.set("numberingSystem", "latn");
            return out;
        });
    }

    // -----------------------------------------------------------------
    // Intl.Segmenter
    // -----------------------------------------------------------------

    private static void installSegmenter(JSObject intl) {
        JSObject proto = new JSObject(Realm.objectPrototype);
        installCtor(intl, "Segmenter", 0, proto, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Intl.Segmenter constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(proto);
            self.set(SLOT_INTL_LOCALE, resolveLocale(Realm.arg(a, 0)));
            self.setAttributes(SLOT_INTL_LOCALE, (byte) 0);
            self.set(SLOT_INTL_OPTIONS, Realm.arg(a, 1));
            self.setAttributes(SLOT_INTL_OPTIONS, (byte) 0);
            return self;
        });
        installMethod(proto, "segment", 1, (t, a, c) -> {
            String input = AbstractOps.toString(Realm.arg(a, 0));
            // Return a basic segments iterable.
            JSObject segments = new JSObject(Realm.iteratorPrototype != null ? Realm.iteratorPrototype : Realm.objectPrototype);
            String granularity = "grapheme";
            if (t instanceof JSObject jo && jo.getOwn(SLOT_INTL_OPTIONS) instanceof JSObject opts) {
                Object g = opts.get("granularity");
                if (g instanceof CharSequence cs) granularity = cs.toString();
            }
            Locale loc = (t instanceof JSObject jo2 && jo2.getOwn(SLOT_INTL_LOCALE) instanceof Locale l) ? l : Locale.getDefault();
            BreakIterator bi;
            switch (granularity) {
                case "word":     bi = BreakIterator.getWordInstance(loc); break;
                case "sentence": bi = BreakIterator.getSentenceInstance(loc); break;
                default:         bi = BreakIterator.getCharacterInstance(loc); break;
            }
            bi.setText(input);
            JSArray cached = new JSArray();
            int start = bi.first();
            while (start != BreakIterator.DONE) {
                int end = bi.next();
                if (end == BreakIterator.DONE) break;
                JSObject seg = new JSObject();
                seg.set("segment", input.substring(start, end));
                seg.set("index", (double) start);
                seg.set("input", input);
                cached.push(seg);
                start = end;
            }
            JSArray finalCached = cached;
            int[] cursor = {0};
            installMethod(segments, "containing", 1, (tt, aa, cc) -> {
                int idx = (int) AbstractOps.toIntegerOrInfinity(Realm.arg(aa, 0));
                for (int i = 0; i < finalCached.length(); i++) {
                    JSObject s = (JSObject) finalCached.get(i);
                    int sIdx = (int) ((Number) s.get("index")).doubleValue();
                    int eIdx = sIdx + ((String) s.get("segment")).length();
                    if (idx >= sIdx && idx < eIdx) return s;
                }
                return Undefined.VALUE;
            });
            segments.set(Realm.wellKnownIterator.asPropertyKey(), nativeFn("@@iterator", 0, (tt, aa, cc) -> {
                JSObject iter = new JSObject(Realm.iteratorPrototype != null ? Realm.iteratorPrototype : Realm.objectPrototype);
                int[] iterCursor = {0};
                installMethod(iter, "next", 0, (ttt, aaa, ccc) -> {
                    JSObject res = new JSObject();
                    if (iterCursor[0] >= finalCached.length()) {
                        res.set("value", Undefined.VALUE);
                        res.set("done", true);
                    } else {
                        res.set("value", finalCached.get(iterCursor[0]++));
                        res.set("done", false);
                    }
                    return res;
                });
                return iter;
            }));
            return segments;
        });
        installMethod(proto, "resolvedOptions", 0, (t, a, c) -> {
            JSObject out = new JSObject();
            Locale l = (t instanceof JSObject jo && jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale ll) ? ll : Locale.getDefault();
            out.set("locale", l.toLanguageTag());
            out.set("granularity", "grapheme");
            return out;
        });
    }

    // -----------------------------------------------------------------
    // Intl.DisplayNames
    // -----------------------------------------------------------------

    private static void installDisplayNames(JSObject intl) {
        JSObject proto = new JSObject(Realm.objectPrototype);
        installCtor(intl, "DisplayNames", 2, proto, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Intl.DisplayNames constructor requires 'new'");
            }
            Object opts = Realm.arg(a, 1);
            if (opts == Undefined.VALUE || !(opts instanceof JSObject)) {
                throw AbruptCompletion.typeError("Intl.DisplayNames: options must be an object");
            }
            Object typeVal = AbstractOps.getProperty(opts, "type");
            if (typeVal == Undefined.VALUE) {
                throw AbruptCompletion.typeError("Intl.DisplayNames: options.type is required");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(proto);
            self.set(SLOT_INTL_LOCALE, resolveLocale(Realm.arg(a, 0)));
            self.setAttributes(SLOT_INTL_LOCALE, (byte) 0);
            self.set(SLOT_INTL_OPTIONS, opts);
            self.setAttributes(SLOT_INTL_OPTIONS, (byte) 0);
            return self;
        });
        installMethod(proto, "of", 1, (t, a, c) -> {
            if (!(t instanceof JSObject jo)) {
                throw AbruptCompletion.typeError("Intl.DisplayNames.of called on non-DisplayNames");
            }
            String code = AbstractOps.toString(Realm.arg(a, 0));
            Locale loc = (jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale l) ? l : Locale.getDefault();
            String type = "language";
            if (jo.getOwn(SLOT_INTL_OPTIONS) instanceof JSObject opts) {
                Object v = opts.get("type");
                if (v instanceof CharSequence cs) type = cs.toString();
            }
            try {
                switch (type) {
                    case "region":   return new Locale("", code).getDisplayCountry(loc);
                    case "script":   return new Locale.Builder().setScript(code).build().getDisplayScript(loc);
                    case "currency": return java.util.Currency.getInstance(code.toUpperCase()).getDisplayName(loc);
                    case "language":
                    default:         return Locale.forLanguageTag(code).getDisplayLanguage(loc);
                }
            } catch (Exception e) {
                return code;
            }
        });
        installMethod(proto, "resolvedOptions", 0, (t, a, c) -> {
            if (!(t instanceof JSObject jo) || !(jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale)
                    || !(jo.getOwn(SLOT_INTL_OPTIONS) instanceof JSObject)) {
                throw AbruptCompletion.typeError("Intl.DisplayNames.resolvedOptions called on non-DisplayNames");
            }
            JSObject out = new JSObject();
            Locale l = (Locale) jo.getOwn(SLOT_INTL_LOCALE);
            out.set("locale", l.toLanguageTag());
            out.set("style", "long");
            out.set("type", "language");
            out.set("fallback", "code");
            return out;
        });
    }

    // -----------------------------------------------------------------
    // Intl.DurationFormat
    // -----------------------------------------------------------------

    private static void installDurationFormat(JSObject intl) {
        JSObject proto = new JSObject(Realm.objectPrototype);
        installCtor(intl, "DurationFormat", 0, proto, (t, a, c) -> {
            if (!Interpreter.isNewCall() && !(t instanceof JSObject)) {
                throw AbruptCompletion.typeError("Intl.DurationFormat constructor requires 'new'");
            }
            JSObject self = (t instanceof JSObject jo) ? jo : new JSObject(proto);
            self.set(SLOT_INTL_LOCALE, resolveLocale(Realm.arg(a, 0)));
            self.setAttributes(SLOT_INTL_LOCALE, (byte) 0);
            return self;
        });
        installMethod(proto, "format", 1, (t, a, c) -> {
            Object dur = Realm.arg(a, 0);
            if (!(dur instanceof JSObject jo)) {
                throw AbruptCompletion.typeError("Intl.DurationFormat.format: duration must be an object");
            }
            StringBuilder sb = new StringBuilder();
            for (String unit : new String[]{"years", "months", "weeks", "days", "hours", "minutes", "seconds"}) {
                Object v = jo.get(unit);
                if (v != Undefined.VALUE && v != null) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(AbstractOps.toString(v)).append(" ").append(unit);
                }
            }
            return sb.length() == 0 ? "0 seconds" : sb.toString();
        });
        installMethod(proto, "formatToParts", 1, (t, a, c) -> {
            JSArray parts = new JSArray();
            JSObject p = new JSObject(); p.set("type", "literal"); p.set("value", "");
            parts.push(p);
            return parts;
        });
        installMethod(proto, "resolvedOptions", 0, (t, a, c) -> {
            JSObject out = new JSObject();
            Locale l = (t instanceof JSObject jo && jo.getOwn(SLOT_INTL_LOCALE) instanceof Locale ll) ? ll : Locale.getDefault();
            out.set("locale", l.toLanguageTag());
            out.set("style", "short");
            return out;
        });
    }
}
