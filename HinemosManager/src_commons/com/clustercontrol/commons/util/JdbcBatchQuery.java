/*

Copyright (C) 2012 NTT DATA Corporation

This program is free software; you can redistribute it and/or
Modify it under the terms of the GNU General Public License
as published by the Free Software Foundation, version 2.

This program is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied
warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
PURPOSE.  See the GNU General Public License for more details.

 */

package com.clustercontrol.commons.util;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Date;

/**
 *  JdbcクエリのSQL文とパラメータを定義する基本クラス
 */
public abstract class JdbcBatchQuery {

	/**
	 * SQL文を取得
	 * 
	 * @return SQL文
	 */
	abstract public String getSql();

	/**
	 * 行いたい処理をバッチに追加
	 * 
	 * @param pstmt JDBCのPreparedStatementオブジェクト
	 * @throws SQLException
	 */
	abstract public void addBatch(PreparedStatement pstmt) throws SQLException;

	/**
	 * オブジェクトpstmtにパラメータを設定
	 * 
	 * @param pstmt JDBCのPreparedStatementオブジェクト
	 * @param params pstmtに設定するパラメータ
	 * @throws SQLException
	 */
	protected void setParameters(PreparedStatement pstmt, Object[] params)
			throws SQLException {
		for (int i = 0; i < params.length; i++) {
			if (params[i] instanceof Date) {
				Date date = (Date) params[i];
				pstmt.setObject(i + 1, new java.sql.Timestamp(date.getTime()));
				continue;
			}

			pstmt.setObject(i + 1, params[i]);
		}
	}
}
