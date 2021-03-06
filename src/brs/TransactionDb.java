package brs;

import brs.schema.tables.records.TransactionRecord;

import java.sql.ResultSet;
import java.util.List;

import org.jooq.DSLContext;

public interface TransactionDb {
  Transaction findTransaction(long transactionId);

  Transaction findTransactionByFullHash(String fullHash);

  boolean hasTransaction(long transactionId);

  boolean hasTransactionByFullHash(String fullHash);

  TransactionImpl loadTransaction(TransactionRecord transactionRecord) throws BurstException.ValidationException;

  TransactionImpl loadTransaction(DSLContext ctx, ResultSet rs) throws BurstException.ValidationException;

  List<TransactionImpl> findBlockTransactions(long blockId);

  void saveTransactions(List<TransactionImpl> transactions);

}
