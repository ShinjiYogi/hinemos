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

package com.clustercontrol.infra.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;

import javax.activation.DataHandler;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyInputStream;
import org.postgresql.copy.PGCopyOutputStream;

import com.clustercontrol.commons.util.JpaTransactionManager;
import com.clustercontrol.fault.HinemosUnknown;
import com.clustercontrol.fault.InfraFileTooLarge;
import com.clustercontrol.maintenance.util.HinemosPropertyUtil;
import com.clustercontrol.util.HinemosTime;

/**
 * JDBCドライバを用いて、高速にinsertまたはupdateの一括処理を行うクラス
 */
public class InfraJdbcExecutor {
	private static final Log log = LogFactory.getLog(InfraJdbcExecutor.class);
	private static final String MAX_FILE_KEY = "infra.max.file.size";

	// PostgreSQL COPY コマンドのバイナリ形式に関する定数
	// 署名(PGCOPY\n\377\r\n\0)
	private static byte[] HEADER_SIGN_PART = {(byte)0x50, (byte)0x47, (byte)0x43, (byte)0x4F, (byte)0x50, (byte)0x59, (byte)0x0A, (byte)0xFF, 
			(byte)0x0D, (byte)0x0A, (byte)0x00 };
	// フラグフィールド
	private static byte[] HEADER_FLG_FIELD_PART = {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
	// ヘッダ拡張領域長
	private static byte[] HEADER_EX_PART = {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
	// タプルのフィールド数
	private static byte[] TUPLE_FIELD_COUNT_PART = {(byte)0x00, (byte)0x02};
	// ファイルトレーラ
	private static byte[] FILETRAILER = {(byte)0xFF, (byte)0xFF};
	
	/**
	 * クエリを実行する
	 * @param query insertまたはupdate
	 * @throws InfraFileTooLarge 
	 * @throws Exception 
	 */
	public static void insertFileContent(String fileId, DataHandler handler) throws HinemosUnknown, InfraFileTooLarge {
		Connection conn = null;
		long start = HinemosTime.currentTimeMillis();
		
		JpaTransactionManager tm = null;
		PGCopyOutputStream pgStream = null;
		FileOutputStream fos = null;
		BufferedInputStream bis = null;
		File tempFile = null;
		try {
			tm = new JpaTransactionManager();
			conn = tm.getEntityManager().unwrap(java.sql.Connection.class);
			conn.setAutoCommit(false);
			
			pgStream = new PGCopyOutputStream((PGConnection)conn, 
					"COPY binarydata.cc_infra_file_content(file_id, file_content) FROM STDIN WITH (FORMAT BINARY)");

			String homeDir = System.getProperty("hinemos.manager.home.dir");
			String exportDirectory = HinemosPropertyUtil.getHinemosPropertyStr("infra.export.dir", homeDir + "/var/export/");
			tempFile = new File(exportDirectory + fileId);
			fos = new FileOutputStream(tempFile);
			handler.writeTo(fos);

			long fileLength = tempFile.length();
			int maxSize = HinemosPropertyUtil.getHinemosPropertyNum(MAX_FILE_KEY , Long.valueOf(1024 * 1024 * 64)).intValue(); // 64MB
			if(fileLength > maxSize) {
				throw new InfraFileTooLarge(String.format("File size is larger than the limit size(%d)", maxSize));
			}
			
			pgStream.write(HEADER_SIGN_PART);
			pgStream.write(HEADER_FLG_FIELD_PART);
			pgStream.write(HEADER_EX_PART);
			pgStream.write(TUPLE_FIELD_COUNT_PART);
			pgStream.write(ByteBuffer.allocate(4).putInt(fileId.getBytes().length).array());
			pgStream.write(fileId.getBytes());
			pgStream.write(ByteBuffer.allocate(4).putInt((int)fileLength).array());
			
			bis = new BufferedInputStream(new FileInputStream(tempFile));
			byte[] buf = new byte[1024*1024];
			int read;
			while ((read = bis.read(buf)) != -1) {
				pgStream.write(buf, 0, read);
			}
			pgStream.write(FILETRAILER);
			pgStream.flush();

			if (! tm.isNestedEm()) {
				conn.commit();
			}
		} catch (InfraFileTooLarge e) {
			log.warn(e.getMessage());
				try {
					pgStream.close();
				} catch (IOException e1) {
					log.warn(e1);
				}
				try {
					conn.rollback();
				} catch (SQLException e1) {
					log.warn(e1);
				}
			throw e;
		} catch (Throwable e) {
			// out of memoryの場合はここを通過する。
			// java.lang.OutOfMemoryError は Exception で catch できないので、Throwableとする。
			log.warn(e.getMessage(), e);
				try {
					if(pgStream != null)
						pgStream.close();
				} catch (IOException e1) {
					log.warn(e1);
				}
				try {
					if (conn != null)
						conn.rollback();
				} catch (SQLException e1) {
					log.warn(e1);
				}
			throw new HinemosUnknown(e.getMessage(), e);
		} finally {
			if(fos != null) {
				try {
					fos.close();
				} catch (IOException e) {
					log.warn(e.getMessage(), e);
					throw new HinemosUnknown(e.getMessage(), e);
				}
			}
			if(bis != null) {
				try {
					bis.close();
				} catch (IOException e) {
					log.warn(e.getMessage(), e);
					throw new HinemosUnknown(e.getMessage(), e);
				}
			}
			if (pgStream != null) {
				try {
					pgStream.close();
				} catch (IOException e) {
					log.warn(e.getMessage(), e);
					throw new HinemosUnknown(e.getMessage(), e);
				}
			}
			if (tm != null) {
				tm.close();
			}
			if (tempFile == null){
				log.debug("Fail to delete. tempFile is null");
			} else if (!tempFile.delete()) {
				log.debug("Fail to delete " + tempFile.getAbsolutePath());
			}
		}
		long time = HinemosTime.currentTimeMillis() - start;
		String message = String.format("Execute [insertFileContent]: %dms",time);
		if (time > 3000) {
			log.warn(message);
		} else if (time > 1000) {
			log.info(message);
		} else {
			log.debug(message);
		}
	}

	public static String selectFileContent(String fileId, String fileName) throws HinemosUnknown {
		Connection conn = null;
		long start = HinemosTime.currentTimeMillis();
		
		JpaTransactionManager tm = null;
		PGCopyInputStream pgStream = null;
		OutputStream fos = null;
		try {
			tm = new JpaTransactionManager();
			tm.begin();
			conn = tm.getEntityManager().unwrap(java.sql.Connection.class);
			conn.setAutoCommit(false);

			String homeDir = System.getProperty("hinemos.manager.home.dir");
			String exportDirectory = HinemosPropertyUtil.getHinemosPropertyStr("infra.export.dir", homeDir + "/var/export/");
			String filepath = exportDirectory + "/" + fileName;
			
			pgStream = new PGCopyInputStream((PGConnection)conn, 
					"COPY (select file_content from binarydata.cc_infra_file_content where file_id = '" + fileId +"') TO STDOUT WITH (FORMAT BINARY)");
			fos = Files.newOutputStream(Paths.get(filepath));
			
			// 署名からタプルのフィールド数(合計21byte)まで読み飛ばす
			long skipLen = pgStream.skip(21);
			if(skipLen != 21) {
				String message = "error in the binary format file parsing (skip tuple from sign) skipLen = " + skipLen;
				log.warn(message);
				throw new HinemosUnknown(message);
			}
			
			byte[] lenBuf = new byte[4];
			int ret = pgStream.read(lenBuf, 0, lenBuf.length);
			if(ret == -1) {
				String message = "error in the binary format file parsing (read file length)";
				log.warn(message);
				throw new HinemosUnknown(message);
			}
			int len = ByteBuffer.wrap(lenBuf).getInt();
			
			byte[] buf = new byte[1024*1024];
			int read;
			int readTotalSize = 0;
			while((read = pgStream.read(buf)) != -1) {
				readTotalSize += read;
				if (readTotalSize > len) {
					// ファイルトレーラの削除
					if((readTotalSize - len) == 2) {
						fos.write(buf, 0, read - 2);
						break;
					} else {
						fos.write(buf, 0, read - 1);
						break;
					}
				} else {
					fos.write(buf, 0, read);
				}
			}
			
			if (! tm.isNestedEm()) {
				conn.commit();
			}
			tm.commit();
			
			long time = HinemosTime.currentTimeMillis() - start;
			String message = String.format("Execute [selectFileContent]: %dms",time);
			if (time > 3000) {
				log.warn(message);
			} else if (time > 1000) {
				log.info(message);
			} else {
				log.debug(message);
			}
			
			return filepath;
		} catch (SQLException | IOException | RuntimeException e) {
			log.warn(e.getMessage(), e);
			if (conn != null) {
				try {
					conn.rollback();
				} catch (SQLException e1) {
					log.warn(e1);
				}
			}
			throw new HinemosUnknown(e.getMessage(), e);
		} finally {
			if (fos != null) {
				try {
					fos.close();
				} catch (IOException e) {
					log.warn(e.getMessage(), e);
					throw new HinemosUnknown(e.getMessage(), e);
				}
			}
			if (pgStream != null) {
				try {
					pgStream.close();
				} catch (IOException e) {
					log.warn(e.getMessage(), e);
					throw new HinemosUnknown(e.getMessage(), e);
				}
			}
			if (tm != null) {
				tm.close();
			}
		}
	}
}
