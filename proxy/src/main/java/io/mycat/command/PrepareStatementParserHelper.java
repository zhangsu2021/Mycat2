/**
 * Copyright (C) <2020>  <chenjunwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */

package io.mycat.command;


import io.mycat.BindValue;
import io.mycat.proxy.session.MycatSession;
import io.vertx.core.Future;

/**
 * @author jamie12221 date 2019-05-12 21:30 实现mysql服务器 预处理相关处理
 **/
public interface PrepareStatementParserHelper {

  Future<Void> handlePrepareStatement(byte[] sql, MycatSession session);

  Future<Void> handlePrepareStatementLongdata(long statementId, int paramId, byte[] data,
                                              MycatSession session);

  Future<Void> handlePrepareStatementExecute(byte[] rawPayload, long statementId, byte flags,
                                             int[] params,
                                             BindValue[] values,
                                             MycatSession session);

  Future<Void> handlePrepareStatementClose(long statementId, MycatSession session);

  Future<Void> handlePrepareStatementFetch(long statementId, long row, MycatSession session);

  Future<Void> handlePrepareStatementReset(long statementId, MycatSession session);

  int getNumParamsByStatementId(long statementId, MycatSession session);



  byte[] getLongData(long statementId, int i, MycatSession mycat);

  BindValue[] getLastBindValue(long statementId, MycatSession mycat);

  void  saveBindValue(long statementId, BindValue[] values, MycatSession mycat);
}
