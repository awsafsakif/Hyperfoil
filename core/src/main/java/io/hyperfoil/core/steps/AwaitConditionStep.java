package io.hyperfoil.core.steps;

import java.util.Collections;
import java.util.List;

import io.hyperfoil.api.config.Step;
import io.hyperfoil.api.session.Access;
import io.hyperfoil.api.session.Session;
import io.hyperfoil.core.builders.BaseStepBuilder;
import io.hyperfoil.core.session.SessionFactory;
import io.hyperfoil.function.SerializableBiPredicate;
import io.hyperfoil.function.SerializablePredicate;

public class AwaitConditionStep implements Step {
   private final SerializablePredicate<Session> condition;

   public AwaitConditionStep(SerializablePredicate<Session> condition) {
      this.condition = condition;
   }

   @Override
   public boolean invoke(Session session) {
      return condition.test(session);
   }

   public static class Builder extends BaseStepBuilder<Builder> {
      private final String var;
      private final SerializableBiPredicate<Session, Access> predicate;

      public Builder(String var, SerializableBiPredicate<Session, Access> predicate) {
         this.var = var;
         this.predicate = predicate;
      }

      @Override
      public Builder copy() {
         return this;
      }

      @Override
      public List<Step> build() {
         Access access = SessionFactory.access(var);
         return Collections.singletonList(new AwaitConditionStep(s -> predicate.test(s, access)));
      }
   }
}
