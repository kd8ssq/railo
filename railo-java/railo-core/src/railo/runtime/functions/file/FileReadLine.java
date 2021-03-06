package railo.runtime.functions.file;

import java.io.IOException;

import railo.runtime.PageContext;
import railo.runtime.exp.FunctionException;
import railo.runtime.exp.PageException;
import railo.runtime.op.Caster;

public class FileReadLine {

	public static String call(PageContext pc, Object fileObj) throws PageException {
		if(!(fileObj instanceof FileStreamWrapper)) 
			throw new FunctionException(pc,"FileReadLine",1,"fileObj",
					"invalid type ["+Caster.toTypeName(fileObj)+"], only File Object produced by FileOpen supported");
		try {
			return ((FileStreamWrapper)fileObj).readLine();
		} catch (IOException e) {
			throw Caster.toPageException(e);
		}
	}
}
