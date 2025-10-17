package org.bsc.langgraph4j.checkpoint;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import oracle.jdbc.OracleStatement;
import oracle.jdbc.OracleType;
import oracle.jdbc.OracleTypes;
import oracle.jdbc.provider.oson.OsonFactory;
import oracle.sql.json.OracleJsonDatum;
import org.bsc.langgraph4j.RunnableConfig;

import javax.sql.DataSource;
import javax.swing.text.html.HTML.Tag;

import java.sql.*;
import java.util.*;

/**
 * <p>
 *   OracleSaver is an extension of MemorySaver that enables persistent,
 *   reliable storage of workflow state in an Oracle database.
 * </p>
 * <p>
 *   Two tables are used to store the workflow state:
 *   <pre>
 *     CREATE TABLE LANGRAPH4J_THREAD (
 *          thread_id VARCHAR2(36) PRIMARY KEY,
 *          thread_name VARCHAR(255),
 *          is_released BOOLEAN DEFAULT FALSE NOT NULL
 *     )
 *     CREATE INDEX IDX_LANGRAPH4J_THREAD_NAME_RELEASED
 *          ON LANGRAPH4J_THREAD(thread_name, is_released)
 *
 *     CREATE TABLE LANGRAPH4J_CHECKPOINT (
 *          checkpoint_id VARCHAR2(36) PRIMARY KEY,
 *          thread_id VARCHAR2(36) NOT NULL,
 *          node_id VARCHAR(255),
 *          next_node_id VARCHAR(255),
 *          state_data JSON NOT NULL,
 *          saved_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
 *
 *          CONSTRAINT LANGRAPH4J_FK_THREAD
 *              FOREIGN KEY(thread_id)
 *              REFERENCES LANGRAPH4J_THREAD(thread_id)
 *              ON DELETE CASCADE
 *     )
 *   </pre>
 * </p>
 * <p>
 *   A builder can be use to create an instance or OracleSaver. The builder
 *   allows to configure the following options:
 *   - DataSource: indicates which data source should be used to connect
 *   to the database
 *   - CreateOption : indicates whether the tables should be created or
 *   existing tables should be used.
 * </p>
 * <p>
 *   Ex:
 *   <pre>
 *     var saver = OracleSaver.builder()
 *         .createOption(CreateOption.CREATE_OR_REPLACE)
 *         .dataSource(DATA_SOURCE)
 *         .build();
 *   </pre>
 * </p>
 */
public class OracleSaver extends MemorySaver {

  // DDL statements
  private static final String CREATE_THREAD_TABLE = """
      CREATE TABLE IF NOT EXISTS LANGRAPH4J_THREAD (
         thread_id VARCHAR2(36) PRIMARY KEY,
         thread_name VARCHAR(255),
         is_released BOOLEAN DEFAULT FALSE NOT NULL
      )""";

  private static final String INDEX_THREAD_TABLE = """
      CREATE INDEX IF NOT EXISTS IDX_LANGRAPH4J_THREAD_NAME_RELEASED
        ON LANGRAPH4J_THREAD(thread_name, is_released)
      """;

  private static final String CREATE_CHECKPOINT_TABLE = """
      CREATE TABLE IF NOT EXISTS LANGRAPH4J_CHECKPOINT (
         checkpoint_id VARCHAR2(36) PRIMARY KEY,
         thread_id VARCHAR2(36) NOT NULL,
         node_id VARCHAR(255),
         next_node_id VARCHAR(255),
         state_data JSON NOT NULL,
         saved_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

         CONSTRAINT LANGRAPH4J_FK_THREAD
             FOREIGN KEY(thread_id)
             REFERENCES LANGRAPH4J_THREAD(thread_id)
             ON DELETE CASCADE
      )""";
  private static final String DROP_THREAD_INDEX =
      "DROP INDEX IDX_LANGRAPH4J_THREAD_NAME_RELEASED";
  private static final String DROP_THREAD_TABLE =
      "DROP TABLE IF EXISTS LANGRAPH4J_THREAD CASCADE CONSTRAINTS";
  private static final String DROP_CHECKPOINT_TABLE =
      "DROP TABLE IF EXISTS LANGRAPH4J_CHECKPOINT CASCADE CONSTRAINTS";

  // DML statements
  private static final String UPSERT_THREAD = """
      MERGE INTO LANGRAPH4J_THREAD existing
      USING (SELECT ? AS THREAD_ID, ? AS THREAD_NAME, FALSE AS IS_RELEASED FROM DUAL) new 
      ON (existing.THREAD_NAME = new.THREAD_NAME AND existing.IS_RELEASED = new.IS_RELEASED)
      WHEN NOT MATCHED THEN INSERT (THREAD_ID, THREAD_NAME, IS_RELEASED) 
      VALUES (new.THREAD_ID, new.THREAD_NAME, new.IS_RELEASED)
      """;

  private static final String INSERT_CHECKPOINT = """
      INSERT INTO LANGRAPH4J_CHECKPOINT(checkpoint_id, thread_id, node_id, next_node_id, state_data)
      SELECT ?, thread_id, ?, ?, ?
      FROM LANGRAPH4J_THREAD
      WHERE THREAD_NAME = ? AND IS_RELEASED = FALSE
      """;

  private static final String UPDATE_CHECKPOINT = """
      UPDATE LANGRAPH4J_CHECKPOINT
      SET
        checkpoint_id = ?,
        node_id = ?,
        next_node_id = ?,
        state_data = ?
      WHERE checkpoint_id = ?
      """;

  private static final String SELECT_CHECKPOINTS = """
      SELECT 
        c.checkpoint_id,
        c.node_id,
        c.next_node_id,
        c.state_data
      FROM LANGRAPH4J_CHECKPOINT c
        INNER JOIN LANGRAPH4J_THREAD t ON c.thread_id = t.thread_id
      WHERE t.thread_name = ? AND t.is_released != TRUE
      ORDER BY c.saved_at DESC 
      """;

  private static final String DELETE_CHECKPOINTS = """
      DELETE FROM LANGRAPH4J_CHECKPOINT WHERE checkpoint_id = ?      
  """;

  private static final String RELEASE_THREAD = """
      UPDATE LANGRAPH4J_THREAD SET is_released = TRUE WHERE thread_name = ? AND is_released = FALSE
      """;

  // Configuration
  private final DataSource dataSource;
  private final CreateOption createOption;

  /**
   * Private constructor used by the builder to create a new instance of
   * OracleSaver.
   * @param dataSource the data source
   * @param createOption the create options
   */
  private OracleSaver(DataSource dataSource, CreateOption createOption) {
    this.dataSource = dataSource;
    this.createOption = createOption;
    initTables();
  }

  /**
   * Creates an instance of a builder that allows to configure and create a new
   * instace of OracleSaver.
   *
   * @return a new instace of the builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * If the list of checkpoints is empty, loads the checkpoints from the database.
   *
   * @param config the configuration
   * @param checkpoints the list of checkpoints
   * @return a list of checkpoints
   * @throws Exception if an error occurs while the checkpoints are being
   * loaded from the database.
   */
  @Override
  protected LinkedList<Checkpoint> loadedCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints) throws Exception {
    if (!checkpoints.isEmpty()) {
      return checkpoints;
    }

    final String threadName = config.threadId().orElse( THREAD_ID_DEFAULT );
    JsonFactory osonFactory = new OsonFactory();
    ObjectMapper objectMapper = new ObjectMapper(osonFactory);

    try (Connection connection = dataSource.getConnection();
         PreparedStatement preparedStatement = connection.prepareStatement(SELECT_CHECKPOINTS)) {
          
      // Calls to defineColumnType reduce the number of network requests. When Oracle JDBC knows that it is
      // fetching VECTOR, CLOB, and/or JSON columns, the first request it sends to the database can include a LOB
      // prefetch size (VECTOR and JSON are value-based-lobs). If defineColumnType is not called, then JDBC needs
      // to send an additional request with the LOB prefetch size, after the first request has the database
      // respond with the column data types. To request all data, the prefetch size is Integer.MAX_VALUE.
      OracleStatement oracleStatement = preparedStatement.unwrap(OracleStatement.class);
      oracleStatement.defineColumnType(1, OracleTypes.VARCHAR); // checkpoint_id
      oracleStatement.defineColumnType(2, OracleTypes.VARCHAR); // node_id
      oracleStatement.defineColumnType(3, OracleTypes.VARCHAR); // next_node_id
      oracleStatement.defineColumnType(4, OracleTypes.JSON, Integer.MAX_VALUE); // state_data
      oracleStatement.setLobPrefetchSize(Integer.MAX_VALUE); // Workaround for Oracle JDBC bug 37030121
      
      preparedStatement.setString(1, threadName);
      try (ResultSet resultSet = preparedStatement.executeQuery()) {
        while (resultSet.next()) {
          byte[] osonBytes = resultSet.getObject(4, OracleJsonDatum.class).shareBytes();
          Checkpoint checkpoint = Checkpoint.builder()
              .id( resultSet.getString(1) )
              .nodeId( resultSet.getString(2) )
              .nextNodeId( resultSet.getString(3) )
              .state(objectMapper.readValue(osonBytes, Map.class))
              .build();
          checkpoints.add(checkpoint);
        }
      }
    } catch (SQLException sqlException) {
      throw new Exception("Unable to create tables", sqlException);
    }
    return checkpoints;
  }

  /**
   * Inserts a checkpoint to the database
   * @param config the configuration
   * @param checkpoints the list of checkpoints
   * @param checkpoint the checkpoint to insert
   * @throws Exception if an error occurs while inserting the checkpoint in the
   * database.
   */
  @Override
  protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) throws Exception {

    final String threadName = config.threadId().orElse( THREAD_ID_DEFAULT );
    try (Connection connection = dataSource.getConnection();
         PreparedStatement upsertStatement = connection.prepareStatement(UPSERT_THREAD);
         PreparedStatement insertCheckpointStatement = connection.prepareStatement(INSERT_CHECKPOINT)) {

      upsertStatement.setString(1, UUID.randomUUID().toString());
      upsertStatement.setString(2, threadName);
      upsertStatement.execute();

      insertCheckpointStatement.setString(1, checkpoint.getId());
      insertCheckpointStatement.setString(2, checkpoint.getNodeId());
      insertCheckpointStatement.setString(3, checkpoint.getNextNodeId());
      insertCheckpointStatement.setObject(4, checkpoint.getState(), OracleType.JSON);
      insertCheckpointStatement.setString(5, threadName);

      insertCheckpointStatement.execute();
    } catch (SQLException sqlException) {
      throw new RuntimeException("Unable to insert checkpoint", sqlException);
    }

  }

  /**
   * Marks the checkpoints as released
   * @param config the configuraiton
   * @param checkpoints the checkpoints
   * @param releaseTag the release tab
   * @throws Exception if an error occurs while marking the checkpoints as released
   */
  @Override
  protected void releasedCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Tag releaseTag) throws Exception {
    final String threadName = config.threadId().orElse( THREAD_ID_DEFAULT );

    try (Connection connection = dataSource.getConnection();
         PreparedStatement preparedStatement = connection.prepareStatement(RELEASE_THREAD)) {
      preparedStatement.setString(1, threadName);
      preparedStatement.execute();
    } catch (SQLException sqlException) {
      throw new Exception("Unable to release checkpoint", sqlException);
    }
  }

  /**
   * If the checkpoint exists, updates the checkpoint, otherwise it inserts it.
   * @param config the configuration
   * @param checkpoints the list of checkpoints
   * @param checkpoint the checkpoint
   * @throws Exception if an error occurs while inserting or updating the
   * checkpoint.
   */
  @Override
  protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) throws Exception {
    final String threadName = config.threadId().orElse( THREAD_ID_DEFAULT );

    if (config.checkPointId().isPresent()) {
      try (Connection connection = dataSource.getConnection();
           PreparedStatement preparedStatement = connection.prepareStatement(UPDATE_CHECKPOINT)) {
        preparedStatement.setString(1, checkpoint.getId());
        preparedStatement.setString(2, checkpoint.getNodeId());
        preparedStatement.setString(3, checkpoint.getNextNodeId());
        preparedStatement.setObject(4, checkpoint.getState(), OracleType.JSON);
        preparedStatement.setString(5, config.checkPointId().get());
        preparedStatement.execute();
      } catch (SQLException sqlException) {
        throw new Exception("Unable to update checkpoint", sqlException);
      }
    } else {
      insertedCheckpoint(config, checkpoints, checkpoint);
    }
  }

  /**
   * Initializes the database according the create options.
   */
  protected void initTables() {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      if (createOption == CreateOption.CREATE_OR_REPLACE) {
        statement.addBatch(DROP_THREAD_INDEX);
        statement.addBatch(DROP_CHECKPOINT_TABLE);
        statement.addBatch(DROP_THREAD_TABLE);
      }
      if (createOption == CreateOption.CREATE_OR_REPLACE ||
          createOption == CreateOption.CREATE_IF_NOT_EXISTS) {
        statement.addBatch(CREATE_THREAD_TABLE);
        statement.addBatch(INDEX_THREAD_TABLE);
        statement.addBatch(CREATE_CHECKPOINT_TABLE);
        statement.executeBatch();
      }
    } catch (SQLException sqlException) {
      throw new RuntimeException("Unable to create tables", sqlException);
    }
  }


  /**
   * A builder for OracleSaver.
   */
  public static class Builder {
    private DataSource dataSource;
    private CreateOption createOption = CreateOption.CREATE_IF_NOT_EXISTS;

    /**
     * Sets the datasource
     * @param dataSource the datasource
     * @return this builder
     */
    public Builder dataSource(DataSource dataSource) {
      this.dataSource = dataSource;
      return this;
    }

    /**
     * Sets the create options (default {@link CreateOption#CREATE_IF_NOT_EXISTS}.
     * @param createOption the create options
     * @return this builder
     */
    public Builder createOption(CreateOption createOption) {
      this.createOption = createOption;
      return this;
    }

    /**
     * Creates a new instance of OracleSaver
     * @return the new instance of OracleSaver.
     */
    public OracleSaver build() {
      return new OracleSaver(dataSource, createOption);
    }
  }
}
