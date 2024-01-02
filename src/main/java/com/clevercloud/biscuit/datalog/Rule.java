package com.clevercloud.biscuit.datalog;

import biscuit.format.schema.Schema;
import com.clevercloud.biscuit.datalog.expressions.Expression;
import com.clevercloud.biscuit.error.Error;
import com.clevercloud.biscuit.error.Error.InvalidType;
import io.vavr.Tuple2;
import io.vavr.Tuple3;
import io.vavr.control.Either;
import io.vavr.control.Option;

import java.io.Serializable;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.vavr.API.Left;
import static io.vavr.API.Right;

public final class Rule implements Serializable {
   private final Predicate head;
   private final List<Predicate> body;
   private final List<Expression> expressions;
   private final List<Scope> scopes;

   public final Predicate head() {
      return this.head;
   }

   public final List<Predicate> body() {
      return this.body;
   }

   public final List<Expression> expressions() {
      return this.expressions;
   }

   public List<Scope> scopes() {
      return scopes;
   }

   public Stream<Either<Error, Tuple2<Origin, Fact>>> apply(
           final Supplier<Stream<Tuple2<Origin, Fact>>> factsSupplier, Long ruleOrigin, SymbolTable symbols) {
      MatchedVariables variables = variablesSet();

      Combinator combinator = new Combinator(variables, this.body, factsSupplier, symbols);
      Spliterator<Tuple2<Origin, Map<Long, Term>>> splitItr = Spliterators
              .spliteratorUnknownSize(combinator, Spliterator.ORDERED);
      Stream<Tuple2<Origin, Map<Long, Term>>> stream = StreamSupport.stream(splitItr, false);

       //somehow we have inference errors when writing this as a lambda
       return stream.map(t -> {
         Origin origin = t._1;
         Map<Long, Term> generatedVariables = t._2;
         TemporarySymbolTable temporarySymbols = new TemporarySymbolTable(symbols);
         for (Expression e : this.expressions) {
            Option<Term> res = e.evaluate(generatedVariables, temporarySymbols);
            if (res.isDefined()) {
               Term term = res.get();
               if (term instanceof Term.Bool) {
                  Term.Bool b = (Term.Bool) term;
                  if (!b.value()) {
                     return Either.right(new Tuple3(origin, generatedVariables, false));
                  }
                  // continue evaluating if true
               } else {
                  return Either.left(new InvalidType());
               }
            }
         }
         return Either.right(new Tuple3(origin, generatedVariables, true));
         /*
      .filter((java.util.function.Predicate<? super Either<? extends Object, ? extends Object>>) new java.util.function.Predicate<Either<Error, Tuple3<Origin, Map<Long, Term>, Boolean>>>() {
         //somehow we have inference errors when writing this as a lambda
         @Override
         public boolean test(Either<Error, Tuple3<Origin, Map<Long, Term>, Boolean>> res) {
            return res.isRight() & res.get()._3.booleanValue();
         }
      })*/
      }).filter((java.util.function.Predicate<? super Either<? extends Object, ? extends Object>>)
               res -> res.isRight() & ((Tuple3<Origin, Map<Long, Term>, Boolean>)res.get())._3.booleanValue()).map(res -> {
         Tuple3<Origin, Map<Long, Term>, Boolean> t = (Tuple3<Origin, Map<Long, Term>, Boolean>) res.get();
         Origin origin = t._1;
         Map<Long, Term> generatedVariables = t._2;

         Predicate p = this.head.clone();
         for (int index = 0; index < p.terms().size(); index++) {
            if (p.terms().get(index) instanceof Term.Variable) {
               Term.Variable var = (Term.Variable) p.terms().get(index);
               if(!generatedVariables.containsKey(var.value())) {
                  //throw new Error("variables that appear in the head should appear in the body as well");
                  return Either.left(new Error.InternalError());
               }
               p.terms().set(index, generatedVariables.get(var.value()));
            }
         }

         origin.add(ruleOrigin);
         return Either.right(new Tuple2<Origin, Fact>(origin, new Fact(p)));
      });
   }

   private MatchedVariables variablesSet() {
      final Set<Long> variables_set = new HashSet<>();

      for (final Predicate pred : this.body) {
         variables_set.addAll(pred.terms().stream().filter((id) -> id instanceof Term.Variable).map((id) -> ((Term.Variable) id).value()).collect(Collectors.toSet()));
      }
      return new MatchedVariables(variables_set);
   }

   // do not produce new facts, only find one matching set of facts
   public boolean find_match(final FactSet facts, Long origin, TrustedOrigins scope, SymbolTable symbols) throws Error {
      MatchedVariables variables = variablesSet();

      if(this.body.isEmpty()) {
         return variables.check_expressions(this.expressions, symbols).isDefined();
      }

      Supplier<Stream<Tuple2<Origin, Fact>>> factsSupplier = () -> facts.iterator(scope);
      Stream<Either<Error, Tuple2<Origin, Fact>>> stream = this.apply(factsSupplier, origin, symbols);

      Iterator<Either<Error, Tuple2<Origin, Fact>>> it = stream.iterator();

      if(!it.hasNext()) {
         return false;
      }

      Either<Error, Tuple2<Origin, Fact>> next = it.next();
      if(next.isRight()) {
         return true;
      } else {
         throw next.getLeft();
      }
   }

   // verifies that the expressions return true for every matching set of facts
   public boolean check_match_all(final FactSet facts, TrustedOrigins scope, SymbolTable symbols) throws InvalidType {
      MatchedVariables variables = variablesSet();

      if(this.body.isEmpty()) {
         return variables.check_expressions(this.expressions, symbols).isDefined();
      }

      Supplier<Stream<Tuple2<Origin, Fact>>> factsSupplier = () -> facts.iterator(scope);
      Combinator combinator = new Combinator(variables, this.body, factsSupplier, symbols);
      boolean found = false;

       for (Combinator it = combinator; it.hasNext(); ) {
           Tuple2<Origin, Map<Long, Term>> t = it.next();
           Map<Long, Term> generatedVariables = t._2;
           found = true;

           TemporarySymbolTable temporarySymbols = new TemporarySymbolTable(symbols);
           for (Expression e : this.expressions) {
              Option<Term> res = e.evaluate(generatedVariables, temporarySymbols);
              if (res.isDefined()) {
                 Term term = res.get();
                 if (term instanceof Term.Bool) {
                    Term.Bool b = (Term.Bool) term;
                    if (!b.value()) {
                       return false;
                    }
                    // continue evaluating if true
                 } else {
                    throw new InvalidType();
                 }
              }
           }
       }
      return found;
   }

   public Rule(final Predicate head, final List<Predicate> body, final List<Expression> expressions) {
      this.head = head;
      this.body = body;
      this.expressions = expressions;
      this.scopes = new ArrayList<>();
   }

   public Rule(final Predicate head, final List<Predicate> body, final List<Expression> expressions,
               final List<Scope> scopes) {
      this.head = head;
      this.body = body;
      this.expressions = expressions;
      this.scopes = scopes;
   }

   public Schema.RuleV2 serialize() {
      Schema.RuleV2.Builder b = Schema.RuleV2.newBuilder()
              .setHead(this.head.serialize());

      for (int i = 0; i < this.body.size(); i++) {
         b.addBody(this.body.get(i).serialize());
      }

      for (int i = 0; i < this.expressions.size(); i++) {
         b.addExpressions(this.expressions.get(i).serialize());
      }

      for (Scope scope: this.scopes) {
         b.addScope(scope.serialize());
      }

      return b.build();
   }

   static public Either<Error.FormatError, Rule> deserializeV2(Schema.RuleV2 rule) {
      ArrayList<Predicate> body = new ArrayList<>();
      for (Schema.PredicateV2 predicate: rule.getBodyList()) {
         Either<Error.FormatError, Predicate> res = Predicate.deserializeV2(predicate);
         if(res.isLeft()) {
            Error.FormatError e = res.getLeft();
            return Left(e);
         } else {
            body.add(res.get());
         }
      }

      ArrayList<Expression> expressions = new ArrayList<>();
      for (Schema.ExpressionV2 expression: rule.getExpressionsList()) {
         Either<Error.FormatError, Expression> res = Expression.deserializeV2(expression);
         if(res.isLeft()) {
            Error.FormatError e = res.getLeft();
            return Left(e);
         } else {
            expressions.add(res.get());
         }
      }

      ArrayList<Scope> scopes = new ArrayList<>();
      for (Schema.Scope scope: rule.getScopeList()) {
         Either<Error.FormatError, Scope> res = Scope.deserialize(scope);
         if(res.isLeft()) {
            Error.FormatError e = res.getLeft();
            return Left(e);
         } else {
            scopes.add(res.get());
         }
      }

      Either<Error.FormatError, Predicate> res = Predicate.deserializeV2(rule.getHead());
      if(res.isLeft()) {
         Error.FormatError e = res.getLeft();
         return Left(e);
      } else {
         return Right(new Rule(res.get(), body, expressions, scopes));
      }
   }
}
