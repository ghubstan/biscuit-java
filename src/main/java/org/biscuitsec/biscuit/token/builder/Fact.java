package org.biscuitsec.biscuit.token.builder;

import io.vavr.control.Option;
import org.biscuitsec.biscuit.datalog.SymbolTable;
import org.biscuitsec.biscuit.error.Error;
import org.biscuitsec.biscuit.error.FailedCheck;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public class Fact implements Cloneable {
    final Predicate predicate;
    final Option<Map<String, Option<Term>>> variables;

    public Fact(String name, List<Term> terms) {
        Map<String, Option<Term>> variables = new HashMap<>();
        for (Term term : terms) {
            if (term instanceof Term.Variable) {
                variables.put(((Term.Variable) term).value, Option.none());
            }
        }
        this.predicate = new Predicate(name, terms);
        this.variables = Option.some(variables);
    }

    public Fact(Predicate p) {
        this.predicate = p;
        this.variables = Option.none();
    }

    private Fact(Predicate predicate, Option<Map<String, Option<Term>>> variables) {
        this.predicate = predicate;
        this.variables = variables;
    }

    public Fact applyVariables() {
        this.variables.forEach(
                vars -> this.predicate.terms = this.predicate.terms.stream().flatMap(t -> {
                    if (t instanceof Term.Variable) {
                        Option<Term> term = vars.getOrDefault(((Term.Variable) t).value, Option.none());
                        return term.map(Stream::of).getOrElse(Stream.empty());
                    } else return Stream.of(t);
                }).collect(toList()));
        return this;
    }

    public org.biscuitsec.biscuit.datalog.Fact convert(SymbolTable symbols) {
        Fact f = this.clone();
        f.applyVariables();
        return new org.biscuitsec.biscuit.datalog.Fact(f.predicate.convert(symbols));
    }

    public static Fact convertFrom(org.biscuitsec.biscuit.datalog.Fact f, SymbolTable symbols) {
        return new Fact(Predicate.convertFrom(f.predicate(), symbols));
    }

    @Override
    public int hashCode() {
        return predicate != null ? predicate.hashCode() : 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Fact fact = (Fact) o;

        return Objects.equals(predicate, fact.predicate);
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public Fact clone() {
        Predicate p = this.predicate.clone();
        Option<Map<String, Option<Term>>> vars = this.variables.map(HashMap::new);
        return new Fact(p, vars);
    }

    @Override
    public String toString() {
        Fact f = this.clone();
        f.applyVariables();
        return f.predicate.toString();
    }

    @SuppressWarnings("unused")
    public String name() {
        return this.predicate.name;
    }

    @SuppressWarnings("unused")
    public Fact set(String name, Term term) throws Error.Language {
        if (this.variables.isEmpty()) {
            throw new Error.Language(new FailedCheck.LanguageError.UnknownVariable(name));
        }
        Map<String, Option<Term>> vars = this.variables.get();
        Option<Term> r = vars.get(name);
        if (r != null) {
            vars.put(name, Option.some(term));
        } else {
            throw new Error.Language(new FailedCheck.LanguageError.UnknownVariable(name));
        }
        return this;
    }

    public List<Term> terms() {
        return this.predicate.terms;
    }

    public void validate() throws Error.Language {
        if (!this.variables.isEmpty()) {
            List<String> invalidVariables = variables.get().entrySet().stream().flatMap(
                    e -> {
                        if (e.getValue().isEmpty()) {
                            return Stream.of(e.getKey());
                        } else {
                            return Stream.empty();
                        }
                    }).collect(toList());
            if (!invalidVariables.isEmpty()) {
                throw new Error.Language(new FailedCheck.LanguageError.Builder(invalidVariables));
            }
        }
    }
}
