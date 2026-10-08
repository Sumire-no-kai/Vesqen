import {DatabaseSync} from 'node:sqlite';
import {readFileSync, readdirSync} from 'node:fs';

/** Real migration/SQL execution, with D1's atomic batch interface in a local SQLite database. */
export class Database {
  constructor() {
    this.db = new DatabaseSync(':memory:');
    this.db.exec('PRAGMA foreign_keys=ON');
    const migrations=new URL('../migrations/',import.meta.url);
    for (const name of readdirSync(migrations).filter(name => name.endsWith('.sql')).sort())
      this.db.exec(readFileSync(new URL(name,migrations),'utf8'));
  }
  prepare(sql) {
    const statement = this.db.prepare(sql);
    return {bind:(...args) => ({
      run:() => statement.run(...args),
      all:async () => ({results:statement.all(...args).map(row => ({...row})), success:true}),
    })};
  }
  async batch(statements) {
    this.db.exec('BEGIN');
    try { const result=statements.map(statement => statement.run());this.db.exec('COMMIT');return result; }
    catch (error) {this.db.exec('ROLLBACK');throw error;}
  }
  rows(sql) {return this.db.prepare(sql).all().map(row => ({...row}));}
  close() {this.db.close();}
}
export function environment() {
  const calls=[];
  return {DB:new Database(),REPORT_RETENTION_DAYS:'7',AGGREGATE_RETENTION_DAYS:'90',UPDATE_MANIFEST_BASE_URL:'',calls,
    USAGE_LIMITER:{limit:async argument => {calls.push(argument);return {success:true};}},
    REPORT_LIMITER:{limit:async argument => {calls.push(argument);return {success:true};}},
  };
}
