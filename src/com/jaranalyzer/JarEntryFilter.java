package com.jaranalyzer;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class JarEntryFilter {

	private JarFile jfile;

	public JarEntryFilter() {
	}

	public JarEntryFilter(JarFile jfile) {
		this.jfile = jfile;
	}

	public List<String> getAllEntriesFromJar() {
		List<String> mass = new ArrayList<>();
		Enumeration<JarEntry> entries = jfile.entries();
		while (entries.hasMoreElements()) {
			JarEntry e = entries.nextElement();
			if (!e.isDirectory()) {
				mass.add(e.getName());
			}
		}
		return mass;
	}

	public JarFile getJfile() {
		return jfile;
	}

	public void setJfile(JarFile jfile) {
		this.jfile = jfile;
	}
}
