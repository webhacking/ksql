/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.rest.server.computation;

import com.google.common.annotations.VisibleForTesting;
import io.confluent.ksql.engine.KsqlEngine;
import io.confluent.ksql.engine.KsqlPlan;
import io.confluent.ksql.exception.ExceptionUtil;
import io.confluent.ksql.parser.KsqlParser.ParsedStatement;
import io.confluent.ksql.parser.KsqlParser.PreparedStatement;
import io.confluent.ksql.parser.tree.CreateAsSelect;
import io.confluent.ksql.parser.tree.CreateTableAsSelect;
import io.confluent.ksql.parser.tree.ExecutableDdlStatement;
import io.confluent.ksql.parser.tree.InsertInto;
import io.confluent.ksql.parser.tree.RunScript;
import io.confluent.ksql.parser.tree.TerminateQuery;
import io.confluent.ksql.planner.plan.ConfiguredKsqlPlan;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.query.id.SpecificQueryIdGenerator;
import io.confluent.ksql.rest.entity.CommandId;
import io.confluent.ksql.rest.entity.CommandStatus;
import io.confluent.ksql.rest.server.StatementParser;
import io.confluent.ksql.rest.server.resources.KsqlConfigurable;
import io.confluent.ksql.rest.util.QueryCapacityUtil;
import io.confluent.ksql.services.ServiceContext;
import io.confluent.ksql.statement.ConfiguredStatement;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlConstants;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.PersistentQueryMetadata;
import io.confluent.ksql.util.QueryMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.streams.StreamsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the actual execution (or delegation to KSQL core) of all distributed statements, as well
 * as tracking their statuses as things move along.
 */
public class InteractiveStatementExecutor implements KsqlConfigurable {

  private static final Logger log = LoggerFactory.getLogger(InteractiveStatementExecutor.class);

  private final ServiceContext serviceContext;
  private final KsqlEngine ksqlEngine;
  private final StatementParser statementParser;
  private final SpecificQueryIdGenerator queryIdGenerator;
  private final Map<CommandId, CommandStatus> statusStore;
  private KsqlConfig ksqlConfig;

  private enum Mode {
    RESTORE,
    EXECUTE
  }

  public InteractiveStatementExecutor(
      final ServiceContext serviceContext,
      final KsqlEngine ksqlEngine,
      final SpecificQueryIdGenerator queryIdGenerator
  ) {
    this(
        serviceContext,
        ksqlEngine,
        new StatementParser(ksqlEngine),
        queryIdGenerator
    );
  }

  @VisibleForTesting
  InteractiveStatementExecutor(
      final ServiceContext serviceContext,
      final KsqlEngine ksqlEngine,
      final StatementParser statementParser,
      final SpecificQueryIdGenerator queryIdGenerator
  ) {
    this.serviceContext = Objects.requireNonNull(serviceContext, "serviceContext");
    this.ksqlEngine = Objects.requireNonNull(ksqlEngine, "ksqlEngine");
    this.statementParser = Objects.requireNonNull(statementParser, "statementParser");
    this.queryIdGenerator = Objects.requireNonNull(queryIdGenerator, "queryIdGenerator");
    this.statusStore = new ConcurrentHashMap<>();
  }

  @Override
  public void configure(final KsqlConfig config) {
    if (!config.getKsqlStreamConfigProps().containsKey(StreamsConfig.APPLICATION_SERVER_CONFIG)) {
      throw new IllegalArgumentException("Need KS application server set");
    }

    ksqlConfig = config;
  }

  KsqlEngine getKsqlEngine() {
    return ksqlEngine;
  }

  /**
   * Attempt to execute a single statement.
   *
   * @param queuedCommand The command to be executed
   */
  void handleStatement(final QueuedCommand queuedCommand) {
    throwIfNotConfigured();

    handleStatementWithTerminatedQueries(
        queuedCommand.getCommand(),
        queuedCommand.getCommandId(),
        queuedCommand.getStatus(),
        Mode.EXECUTE,
        queuedCommand.getOffset()
    );
  }

  void handleRestore(final QueuedCommand queuedCommand) {
    throwIfNotConfigured();

    handleStatementWithTerminatedQueries(
        queuedCommand.getCommand(),
        queuedCommand.getCommandId(),
        queuedCommand.getStatus(),
        Mode.RESTORE,
        queuedCommand.getOffset()
    );
  }

  /**
   * Get details on the statuses of all the statements handled thus far.
   *
   * @return A map detailing the current statuses of all statements that the handler has executed
   *     (or attempted to execute).
   */
  public Map<CommandId, CommandStatus> getStatuses() {
    return new HashMap<>(statusStore);
  }

  /**
   * @param statementId The ID of the statement to check the status of.
   * @return Information on the status of the statement with the given ID, if one exists.
   */
  public Optional<CommandStatus> getStatus(final CommandId statementId) {
    return Optional.ofNullable(statusStore.get(statementId));
  }

  private void throwIfNotConfigured() {
    if (ksqlConfig == null) {
      throw new IllegalStateException("No initialized");
    }
  }

  private void putStatus(final CommandId commandId,
                        final Optional<CommandStatusFuture> commandStatusFuture,
                        final CommandStatus status) {
    statusStore.put(commandId, status);
    commandStatusFuture.ifPresent(s -> s.setStatus(status));
  }

  private void putFinalStatus(final CommandId commandId,
                             final Optional<CommandStatusFuture> commandStatusFuture,
                             final CommandStatus status) {
    statusStore.put(commandId, status);
    commandStatusFuture.ifPresent(s -> s.setFinalStatus(status));
  }

  /**
   * Attempt to execute a single statement.
   *
   * @param command The string containing the statement to be executed
   * @param commandId The ID to be used to track the status of the command
   * @param mode was this table/stream subsequently dropped
   */
  private void handleStatementWithTerminatedQueries(
      final Command command,
      final CommandId commandId,
      final Optional<CommandStatusFuture> commandStatusFuture,
      final Mode mode,
      final long offset
  ) {
    try {
      final String statementString = command.getStatement();
      putStatus(
          commandId,
          commandStatusFuture,
          new CommandStatus(CommandStatus.Status.PARSING, "Parsing statement"));
      final PreparedStatement<?> statement = statementParser.parseSingleStatement(statementString);
      putStatus(
          commandId,
          commandStatusFuture,
          new CommandStatus(CommandStatus.Status.EXECUTING, "Executing statement")
      );
      executeStatement(
          statement, command, commandId, commandStatusFuture, mode, offset);
    } catch (final KsqlException exception) {
      log.error("Failed to handle: " + command, exception);
      final CommandStatus errorStatus = new CommandStatus(
          CommandStatus.Status.ERROR,
          ExceptionUtil.stackTraceToString(exception)
      );
      putFinalStatus(commandId, commandStatusFuture, errorStatus);
    }
  }

  @SuppressWarnings("unchecked")
  private void executeStatement(
      final PreparedStatement<?> statement,
      final Command command,
      final CommandId commandId,
      final Optional<CommandStatusFuture> commandStatusFuture,
      final Mode mode,
      final long offset
  ) {
    String successMessage = "";
    if (statement.getStatement() instanceof ExecutableDdlStatement) {
      successMessage = executeDdlStatement(statement, command);
    } else if (statement.getStatement() instanceof CreateAsSelect) {
      final PersistentQueryMetadata query = startQuery(statement, command, mode, offset);
      final String name = ((CreateAsSelect)statement.getStatement()).getName().name();
      successMessage = statement.getStatement() instanceof CreateTableAsSelect
          ? "Table " + name + " created and running" : "Stream " + name + " created and running";
      successMessage += ". Created by query with query ID: " + query.getQueryId();
    } else if (statement.getStatement() instanceof InsertInto) {
      final PersistentQueryMetadata query = startQuery(statement, command, mode, offset);
      successMessage = "Insert Into query is running with query ID: " + query.getQueryId();
    } else if (statement.getStatement() instanceof TerminateQuery) {
      terminateQuery((PreparedStatement<TerminateQuery>) statement);
      successMessage = "Query terminated.";
    } else if (statement.getStatement() instanceof RunScript) {
      handleLegacyRunScript(command, mode);
    } else {
      throw new KsqlException(String.format(
          "Unexpected statement type: %s",
          statement.getClass().getName()
      ));
    }

    final CommandStatus successStatus =
        new CommandStatus(CommandStatus.Status.SUCCESS, successMessage);

    putFinalStatus(commandId, commandStatusFuture, successStatus);
  }

  private String executeDdlStatement(final PreparedStatement<?> statement, final Command command) {
    final KsqlConfig mergedConfig = buildMergedConfig(command);
    final ConfiguredStatement<?> configured =
        ConfiguredStatement.of(statement, command.getOverwriteProperties(), mergedConfig);

    final KsqlPlan plan = ksqlEngine.plan(serviceContext, configured);
    return ksqlEngine
        .execute(
            serviceContext,
            ConfiguredKsqlPlan.of(plan, command.getOverwriteProperties(), mergedConfig))
        .getCommandResult()
        .get();
  }

  /**
   * @deprecated deprecate since 5.2. `RUN SCRIPT` will be removed from syntax in later release.
   */
  @SuppressWarnings("DeprecatedIsStillUsed")
  @Deprecated
  private void handleLegacyRunScript(final Command command, final Mode mode) {

    final String sql = (String) command.getOverwriteProperties()
        .get(KsqlConstants.LEGACY_RUN_SCRIPT_STATEMENTS_CONTENT);

    if (sql == null) {
      throw new KsqlException("No statements received for LOAD FROM FILE.");
    }

    final Map<String, Object> overriddenProperties = new HashMap<>(
        command.getOverwriteProperties());

    final KsqlConfig mergedConfig = buildMergedConfig(command);

    final List<QueryMetadata> queries = new ArrayList<>();
    for (final ParsedStatement parsed : ksqlEngine.parse(sql)) {
      final PreparedStatement<?> prepared = ksqlEngine.prepare(parsed);
      final ConfiguredStatement<?> configured =
          ConfiguredStatement.of(prepared, overriddenProperties, ksqlConfig);
      ksqlEngine.execute(serviceContext, configured)
          .getQuery()
          .ifPresent(queries::add);
    }

    if (QueryCapacityUtil.exceedsPersistentQueryCapacity(ksqlEngine, mergedConfig, 0)) {
      queries.forEach(QueryMetadata::close);
      QueryCapacityUtil.throwTooManyActivePersistentQueriesException(
          ksqlEngine, mergedConfig, command.getStatement());
    }

    if (mode == Mode.EXECUTE) {
      for (final QueryMetadata queryMetadata : queries) {
        if (queryMetadata instanceof PersistentQueryMetadata) {
          final PersistentQueryMetadata persistentQueryMd =
              (PersistentQueryMetadata) queryMetadata;
          persistentQueryMd.start();
        }
      }
    }
  }

  private PersistentQueryMetadata startQuery(
      final PreparedStatement<?> statement,
      final Command command,
      final Mode mode,
      final long offset
  ) {
    final KsqlConfig mergedConfig = buildMergedConfig(command);

    final ConfiguredStatement<?> configured = ConfiguredStatement.of(
        statement, command.getOverwriteProperties(), mergedConfig);

    queryIdGenerator.setNextId(offset);

    final KsqlPlan plan = ksqlEngine.plan(serviceContext, configured);
    final QueryMetadata queryMetadata =
        ksqlEngine
            .execute(
                serviceContext,
                ConfiguredKsqlPlan.of(plan, command.getOverwriteProperties(), mergedConfig))
            .getQuery()
            .orElseThrow(() -> new IllegalStateException("Statement did not return a query"));

    if (!(queryMetadata instanceof PersistentQueryMetadata)) {
      throw new KsqlException(String.format(
          "Unexpected query metadata type: %s; was expecting %s",
          queryMetadata.getClass().getCanonicalName(),
          PersistentQueryMetadata.class.getCanonicalName()
      ));
    }

    final PersistentQueryMetadata persistentQueryMd = (PersistentQueryMetadata) queryMetadata;
    if (mode == Mode.EXECUTE) {
      persistentQueryMd.start();
    }
    return persistentQueryMd;
  }

  private KsqlConfig buildMergedConfig(final Command command) {
    return ksqlConfig.overrideBreakingConfigsWithOriginalValues(command.getOriginalProperties());
  }

  private void terminateQuery(final PreparedStatement<TerminateQuery> terminateQuery) {
    final Optional<QueryId> queryId = terminateQuery.getStatement().getQueryId();

    if (!queryId.isPresent()) {
      ksqlEngine.getPersistentQueries().forEach(PersistentQueryMetadata::close);
      return;
    }

    ksqlEngine.getPersistentQuery(queryId.get())
        .orElseThrow(() ->
            new KsqlException(String.format("No running query with id %s was found", queryId)))
        .close();
  }

}
